using System.Text.Json;
using System.Text.RegularExpressions;
using OnFinApp.Api.Common;
using OnFinApp.Api.Instruments;
using OnFinApp.Api.Llm;

namespace OnFinApp.Api.Analyses;

public sealed record KeyFigure(string Label, string Value, string AsOf);

/// The body of an analysis, as stored in analyses.content.
public sealed record AnalysisContent(
    string Summary,
    IReadOnlyList<string> BullPoints,
    IReadOnlyList<string> BearPoints,
    IReadOnlyList<string> Risks,
    IReadOnlyList<KeyFigure> KeyFigures);

/// An LLM answer that passed every check and is ready to store.
public sealed record GeneratedAnalysis(
    AnalysisContent Content, IReadOnlyList<Source> Sources, string Rating, string Confidence, string Rationale);

/// How a generation went: stored with the analysis and written to the log.
public sealed record GenerationStats(string Model, int DurationMs, int InputTokens, int OutputTokens);

/// <param name="Fresh">Not expired yet.</param>
/// <param name="Young">Younger than the minimum age for a user refresh.</param>
public sealed record StoredAnalysis(
    AnalysisContent Content,
    IReadOnlyList<Source> Sources,
    string Rating,
    string Confidence,
    string Rationale,
    DateTime GeneratedAt,
    DateTime ExpiresAt,
    string GeneratedBy,
    bool Fresh,
    bool Young);

public sealed record RatingRow(long Seq, string Rating, string Confidence, string Rationale, DateTime CreatedAt);

public static class AnalysisStatuses
{
    public const string Ready = "READY";
    public const string Generating = "GENERATING";
    public const string Failed = "FAILED";
}

// Response bodies, as defined in the shared contract.

public sealed record AnalysisResponse(
    string Summary,
    IReadOnlyList<string> BullPoints,
    IReadOnlyList<string> BearPoints,
    IReadOnlyList<string> Risks,
    IReadOnlyList<KeyFigure> KeyFigures,
    IReadOnlyList<Source> Sources,
    string Rating,
    string Confidence,
    string Rationale,
    DateTime GeneratedAt,
    DateTime ExpiresAt,
    string GeneratedBy)
{
    public static AnalysisResponse From(StoredAnalysis a) => new(
        a.Content.Summary, a.Content.BullPoints, a.Content.BearPoints, a.Content.Risks, a.Content.KeyFigures,
        a.Sources, a.Rating, a.Confidence, a.Rationale, a.GeneratedAt, a.ExpiresAt, a.GeneratedBy);
}

public sealed record AnalysisStatusResponse(string Symbol, string Status, AnalysisResponse? Analysis)
{
    public static AnalysisStatusResponse Of(Instrument instrument, string status, StoredAnalysis? latest) =>
        new(instrument.Symbol, status, latest is null ? null : AnalysisResponse.From(latest));
}

public sealed record RatingEntryResponse(string Rating, string Confidence, string Rationale, DateTime CreatedAt);

public sealed record RatingPageResponse(IReadOnlyList<RatingEntryResponse> Items, string? NextCursor);

/// Thrown when an LLM answer fails the checks in <see cref="AnalysisParser"/>.
public sealed class InvalidAnalysisException(string reason) : Exception($"The LLM answer is not valid: {reason}");

/// Turns an LLM answer into a <see cref="GeneratedAnalysis"/>, or rejects it.
/// The rules are listed in shared/llm/README.md and are the same in the Java API.
public static partial class AnalysisParser
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly string[] Ratings = ["BUY", "HOLD", "SELL"];
    private static readonly string[] Confidences = ["LOW", "MEDIUM", "HIGH"];

    // The answer as the LLM wrote it: anything may be missing.
    private sealed record Answer(
        string? Summary,
        List<string?>? BullPoints,
        List<string?>? BearPoints,
        List<string?>? Risks,
        List<AnswerFigure?>? KeyFigures,
        string? Rating,
        string? Confidence,
        string? Rationale,
        List<AnswerSource?>? Sources);

    private sealed record AnswerFigure(string? Label, string? Value, string? AsOf);

    private sealed record AnswerSource(string? Title, string? Url);

    public static GeneratedAnalysis Parse(LlmResult result)
    {
        Answer answer;
        try
        {
            answer = JsonSerializer.Deserialize<Answer>(result.Json, Json) ?? throw new InvalidAnalysisException("it is empty.");
        }
        catch (JsonException)
        {
            throw new InvalidAnalysisException("it is not JSON of the expected shape.");
        }

        var content = new AnalysisContent(
            Text(answer.Summary, "summary"),
            Points(answer.BullPoints, "bullPoints"),
            Points(answer.BearPoints, "bearPoints"),
            Points(answer.Risks, "risks"),
            (answer.KeyFigures ?? throw new InvalidAnalysisException("keyFigures is missing."))
                .Select(figure => new KeyFigure(
                    Text(figure?.Label, "a key figure label"),
                    Text(figure?.Value, "a key figure value"),
                    figure?.AsOf is { } asOf && IsoDate().IsMatch(asOf)
                        ? asOf
                        : throw new InvalidAnalysisException("a key figure has no asOf date written as YYYY-MM-DD.")))
                .ToList());

        if (!Ratings.Contains(answer.Rating)) throw new InvalidAnalysisException("rating must be BUY, HOLD or SELL.");
        if (!Confidences.Contains(answer.Confidence)) throw new InvalidAnalysisException("confidence must be LOW, MEDIUM or HIGH.");

        // A rating must rest on sources, and only on pages the search tool really returned.
        if (answer.Sources is not { Count: > 0 }) throw new InvalidAnalysisException("it cites no sources.");
        var returned = result.Sources.Select(source => source.Url).ToHashSet();
        var sources = answer.Sources.Select(source =>
        {
            var url = source?.Url;
            if (url is null || !(url.StartsWith("https://", StringComparison.Ordinal) || url.StartsWith("http://", StringComparison.Ordinal)))
            {
                throw new InvalidAnalysisException("a source has no http(s) URL.");
            }
            if (!returned.Contains(url)) throw new InvalidAnalysisException("it cites a source the search tool did not return.");
            return new Source(Text(source?.Title, "a source title"), url);
        }).ToList();

        return new GeneratedAnalysis(content, sources, answer.Rating!, answer.Confidence!, Text(answer.Rationale, "rationale"));
    }

    private static string Text(string? value, string what) =>
        string.IsNullOrWhiteSpace(value) ? throw new InvalidAnalysisException($"{what} is missing or blank.") : value;

    private static List<string> Points(List<string?>? values, string what) =>
        values is { Count: > 0 }
            ? values.Select(value => Text(value, $"an entry of {what}")).ToList()
            : throw new InvalidAnalysisException($"{what} is missing or empty.");

    [GeneratedRegex(@"^\d{4}-\d{2}-\d{2}$")]
    private static partial Regex IsoDate();
}

/// Position in a rating history: the sequence number of the last rating already returned.
/// Clients see it as an opaque base64url string.
public static class RatingCursor
{
    /// Before the newest rating.
    public const long Start = long.MaxValue;

    public static string Encode(long seq) =>
        Microsoft.AspNetCore.WebUtilities.WebEncoders.Base64UrlEncode(
            System.Text.Encoding.UTF8.GetBytes(seq.ToString(System.Globalization.CultureInfo.InvariantCulture)));

    /// Throws <see cref="InvalidCursorException"/> for anything that <see cref="Encode"/> did not produce.
    public static long Decode(string cursor)
    {
        try
        {
            var raw = System.Text.Encoding.UTF8.GetString(Microsoft.AspNetCore.WebUtilities.WebEncoders.Base64UrlDecode(cursor));
            if (raw.Length is >= 1 and <= 18
                && long.TryParse(raw, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var seq)
                && seq > 0)
            {
                return seq;
            }
        }
        catch (FormatException)
        {
            // not base64url: falls through to the error below
        }
        throw new InvalidCursorException();
    }
}
