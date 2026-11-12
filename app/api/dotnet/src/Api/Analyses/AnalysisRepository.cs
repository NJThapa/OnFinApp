using System.Text.Json;
using Npgsql;
using OnFinApp.Api.Llm;

namespace OnFinApp.Api.Analyses;

public interface IAnalysisRepository
{
    /// The newest analysis of an instrument with its rating, or null when none exists.
    Task<StoredAnalysis?> FindLatestAsync(Guid instrumentId, int minAgeSeconds, CancellationToken cancel);

    /// The current rating of each instrument that has one.
    Task<IReadOnlyDictionary<Guid, string>> LatestRatingsAsync(IReadOnlyCollection<Guid> instrumentIds, CancellationToken cancel);

    /// Ratings older than <paramref name="beforeSeq"/>, newest first.
    Task<IReadOnlyList<RatingRow>> ListRatingsAsync(Guid instrumentId, long beforeSeq, int limit, CancellationToken cancel);

    /// Stores the analysis and its rating and marks the job done, in one transaction.
    /// False, with nothing stored, when the job is no longer running (another worker took it over).
    Task<bool> CompleteAsync(
        Guid jobId, Guid instrumentId, GeneratedAnalysis analysis, GenerationStats stats, string generatedBy, int ttlSeconds,
        CancellationToken cancel);
}

public sealed class AnalysisRepository(NpgsqlDataSource db) : IAnalysisRepository
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private const string FindLatest = """
        select a.content::text, a.sources::text, r.rating, r.confidence, r.rationale,
               a.generated_at, a.expires_at, a.generated_by,
               a.expires_at > now(), a.generated_at > now() - make_interval(secs => $2::int)
        from ratings r join analyses a on a.id = r.analysis_id
        where r.instrument_id = $1
        order by r.seq desc
        limit 1
        """;

    private const string LatestRatings = """
        select distinct on (instrument_id) instrument_id, rating
        from ratings
        where instrument_id = any($1)
        order by instrument_id, seq desc
        """;

    private const string ListRatings = """
        select seq, rating, confidence, rationale, created_at
        from ratings
        where instrument_id = $1 and seq < $2
        order by seq desc
        limit $3
        """;

    private const string MarkJobDone = """
        update generation_jobs set status = 'DONE', error = null, finished_at = now()
        where id = $1 and status = 'RUNNING'
        """;

    // Times are cut to whole seconds so both APIs write them the same way in responses.
    private const string Insert = """
        with a as (
            insert into analyses (instrument_id, content, sources, generated_at, expires_at,
                                  generated_by, model, duration_ms, input_tokens, output_tokens)
            values ($1, $2::jsonb, $3::jsonb, date_trunc('second', now()),
                    date_trunc('second', now()) + make_interval(secs => $4::int), $5, $6, $7, $8, $9)
            returning id, instrument_id, generated_at
        )
        insert into ratings (instrument_id, analysis_id, rating, confidence, rationale, created_at)
        select instrument_id, id, $10, $11, $12, generated_at from a
        """;

    public async Task<StoredAnalysis?> FindLatestAsync(Guid instrumentId, int minAgeSeconds, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(FindLatest);
        cmd.Parameters.Add(new() { Value = instrumentId });
        cmd.Parameters.Add(new() { Value = minAgeSeconds });
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        if (!await reader.ReadAsync(cancel)) return null;
        return new StoredAnalysis(
            Parse<AnalysisContent>(reader.GetString(0)),
            Parse<List<Source>>(reader.GetString(1)),
            reader.GetString(2), reader.GetString(3), reader.GetString(4),
            reader.GetDateTime(5), reader.GetDateTime(6), reader.GetString(7),
            reader.GetBoolean(8), reader.GetBoolean(9));
    }

    public async Task<IReadOnlyDictionary<Guid, string>> LatestRatingsAsync(
        IReadOnlyCollection<Guid> instrumentIds, CancellationToken cancel)
    {
        var ratings = new Dictionary<Guid, string>();
        if (instrumentIds.Count == 0) return ratings;

        await using var cmd = db.CreateCommand(LatestRatings);
        cmd.Parameters.Add(new() { Value = instrumentIds.ToArray() });
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        while (await reader.ReadAsync(cancel)) ratings[reader.GetGuid(0)] = reader.GetString(1);
        return ratings;
    }

    public async Task<IReadOnlyList<RatingRow>> ListRatingsAsync(Guid instrumentId, long beforeSeq, int limit, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(ListRatings);
        cmd.Parameters.Add(new() { Value = instrumentId });
        cmd.Parameters.Add(new() { Value = beforeSeq });
        cmd.Parameters.Add(new() { Value = limit });
        var rows = new List<RatingRow>();
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        while (await reader.ReadAsync(cancel))
        {
            rows.Add(new RatingRow(
                reader.GetInt64(0), reader.GetString(1), reader.GetString(2), reader.GetString(3), reader.GetDateTime(4)));
        }
        return rows;
    }

    public async Task<bool> CompleteAsync(
        Guid jobId, Guid instrumentId, GeneratedAnalysis analysis, GenerationStats stats, string generatedBy, int ttlSeconds,
        CancellationToken cancel)
    {
        await using var connection = await db.OpenConnectionAsync(cancel);
        await using var transaction = await connection.BeginTransactionAsync(cancel);

        await using (var done = new NpgsqlCommand(MarkJobDone, connection, transaction))
        {
            done.Parameters.Add(new() { Value = jobId });
            if (await done.ExecuteNonQueryAsync(cancel) == 0) return false;
        }

        await using (var insert = new NpgsqlCommand(Insert, connection, transaction))
        {
            insert.Parameters.Add(new() { Value = instrumentId });
            insert.Parameters.Add(new() { Value = JsonSerializer.Serialize(analysis.Content, Json) });
            insert.Parameters.Add(new() { Value = JsonSerializer.Serialize(analysis.Sources, Json) });
            insert.Parameters.Add(new() { Value = ttlSeconds });
            insert.Parameters.Add(new() { Value = generatedBy });
            insert.Parameters.Add(new() { Value = stats.Model });
            insert.Parameters.Add(new() { Value = stats.DurationMs });
            insert.Parameters.Add(new() { Value = stats.InputTokens });
            insert.Parameters.Add(new() { Value = stats.OutputTokens });
            insert.Parameters.Add(new() { Value = analysis.Rating });
            insert.Parameters.Add(new() { Value = analysis.Confidence });
            insert.Parameters.Add(new() { Value = analysis.Rationale });
            await insert.ExecuteNonQueryAsync(cancel);
        }

        await transaction.CommitAsync(cancel);
        return true;
    }

    private static T Parse<T>(string json) =>
        JsonSerializer.Deserialize<T>(json, Json) ?? throw new InvalidOperationException("A stored analysis is empty.");
}
