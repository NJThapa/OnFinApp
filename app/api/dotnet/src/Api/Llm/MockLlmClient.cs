using System.Text.Json;

namespace OnFinApp.Api.Llm;

/// Deterministic invented analyses; no network. The rules are specified in shared/llm/README.md
/// and implemented identically in the Java API.
public sealed class MockLlmClient(TimeProvider clock, TimeSpan delay, IReadOnlySet<string> failSymbols) : ILlmClient
{
    public const string ModelName = "mock";

    private static readonly TimeZoneInfo NewYork = TimeZoneInfo.FindSystemTimeZoneById("America/New_York");
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly string[] Ratings = ["BUY", "HOLD", "SELL"];
    private static readonly string[] Confidences = ["LOW", "MEDIUM", "HIGH"];
    private static readonly string[] RationaleEndings = ["point up.", "are mixed.", "point down."];

    private sealed record KeyFigure(string Label, string Value, string AsOf);

    private sealed record Answer(
        string Summary,
        IReadOnlyList<string> BullPoints,
        IReadOnlyList<string> BearPoints,
        IReadOnlyList<string> Risks,
        IReadOnlyList<KeyFigure> KeyFigures,
        string Rating,
        string Confidence,
        string Rationale,
        IReadOnlyList<Source> Sources);

    public async Task<LlmResult> GenerateAsync(LlmRequest request, CancellationToken cancel)
    {
        var symbol = request.Variables["symbol"];
        var name = request.Variables["name"];
        var etf = request.Variables["type"] == "ETF";

        if (delay > TimeSpan.Zero) await Task.Delay(delay, cancel);
        if (failSymbols.Contains(symbol)) throw new LlmException($"The mock LLM is set to fail for {symbol}.");

        long h = 7;
        foreach (var c in symbol) h = (h * 31 + c) % 100003;
        var today = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(clock.GetUtcNow(), NewYork).DateTime);
        long d = today.DayNumber - new DateOnly(1970, 1, 1).DayNumber;
        var date = today.ToString("yyyy-MM-dd");
        var rating = (int)((h + d) % 3);

        IReadOnlyList<Source> sources =
        [
            new($"{name} investor relations (mock)", $"https://example.com/mock/{symbol}/investor-relations"),
            new($"{symbol} latest quarterly report (mock)", $"https://example.com/mock/{symbol}/quarterly-report"),
            new($"Market commentary on {symbol} (mock)", $"https://example.com/mock/{symbol}/market-commentary"),
        ];

        var answer = new Answer(
            Summary: etf
                ? $"Mock analysis of the fund {name} ({symbol}). This text is generated from fixtures by the mock LLM; it is not real research."
                : $"Mock analysis of {name} ({symbol}). This text is generated from fixtures by the mock LLM; it is not real research.",
            BullPoints: etf
                ?
                [
                    $"Mock bull point: {symbol} spreads its holdings widely.",
                    "Mock bull point: the expense ratio is low for its category.",
                ]
                :
                [
                    $"Mock bull point: {name} keeps growing revenue in its core business.",
                    "Mock bull point: margins leave room to keep investing.",
                    "Mock bull point: the balance sheet is strong.",
                ],
            BearPoints: etf
                ?
                [
                    $"Mock bear point: the largest holdings make up a big share of {symbol}.",
                    "Mock bear point: the recent trend has been flat.",
                ]
                :
                [
                    $"Mock bear point: {symbol} trades at a high valuation.",
                    "Mock bear point: growth is slowing in the largest segment.",
                ],
            Risks: etf
                ? ["Mock risk: a broad market decline.", "Mock risk: concentration in a few sectors."]
                : ["Mock risk: tougher competition.", "Mock risk: regulatory changes."],
            KeyFigures: etf
                ?
                [
                    new("Expense ratio", Hundredths(3 + h % 70) + "%", date),
                    new("Top 10 holdings weight", Tenths(150 + h % 450) + "%", date),
                    new("1-year return", Tenths(h % 400 - 100) + "%", date),
                ]
                :
                [
                    new("Revenue growth (YoY)", Tenths(h % 400 - 100) + "%", date),
                    new("Operating margin", Tenths(50 + h % 350) + "%", date),
                    new("P/E ratio", Tenths(80 + h % 420), date),
                ],
            Rating: Ratings[rating],
            Confidence: Confidences[(int)(h / 7 % 3)],
            Rationale: $"Mock rationale: the invented figures for {symbol} {RationaleEndings[rating]}",
            Sources: sources);

        return new LlmResult(
            JsonSerializer.Serialize(answer, Json), sources,
            InputTokens: request.Prompt.Length / 4, OutputTokens: (int)(400 + h % 200), ModelName);
    }

    private static string Tenths(long value)
    {
        var abs = Math.Abs(value);
        return $"{(value < 0 ? "-" : "")}{abs / 10}.{abs % 10}";
    }

    private static string Hundredths(long value) => $"{value / 100}.{value % 100:00}";
}
