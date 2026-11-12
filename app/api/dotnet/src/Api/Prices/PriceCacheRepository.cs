using Npgsql;
using NpgsqlTypes;

namespace OnFinApp.Api.Prices;

/// A stored provider response and whether it is still fresh.
public sealed record CachedPayload(string Json, bool Fresh);

/// The shared price_cache table. Both APIs read and write it, so they reuse each other's fetches.
public interface IPriceCacheRepository
{
    public const string QuoteKey = "QUOTE";

    /// Entry for one instrument and key. Fresh means younger than <paramref name="ttlSeconds"/>, or,
    /// when that is null, fetched on the current New York calendar day.
    Task<CachedPayload?> ReadAsync(Guid instrumentId, string key, int? ttlSeconds, CancellationToken cancel);

    Task<IReadOnlyDictionary<Guid, CachedPayload>> ReadQuotesAsync(
        IReadOnlyCollection<Guid> instrumentIds, int ttlSeconds, CancellationToken cancel);

    Task WriteAsync(Guid instrumentId, string key, string json, CancellationToken cancel);
}

public sealed class PriceCacheRepository(NpgsqlDataSource db) : IPriceCacheRepository
{
    private const string Fresh = """
        case when $1::int is not null then fetched_at > now() - make_interval(secs => $1::int)
             else (fetched_at at time zone 'America/New_York')::date = (now() at time zone 'America/New_York')::date
        end
        """;
    private const string Read = $"select payload::text, {Fresh} from price_cache where instrument_id = $2 and range = $3";
    private const string ReadQuotes =
        $"select instrument_id, payload::text, {Fresh} from price_cache where range = 'QUOTE' and instrument_id = any($2)";
    private const string Write = """
        insert into price_cache (instrument_id, range, payload, fetched_at)
        values ($1, $2, $3::jsonb, now())
        on conflict (instrument_id, range)
        do update set payload = excluded.payload, fetched_at = excluded.fetched_at
        """;

    public async Task<CachedPayload?> ReadAsync(Guid instrumentId, string key, int? ttlSeconds, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(Read);
        cmd.Parameters.Add(new() { Value = (object?)ttlSeconds ?? DBNull.Value, NpgsqlDbType = NpgsqlDbType.Integer });
        cmd.Parameters.Add(new() { Value = instrumentId });
        cmd.Parameters.Add(new() { Value = key });
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        return await reader.ReadAsync(cancel) ? new CachedPayload(reader.GetString(0), reader.GetBoolean(1)) : null;
    }

    public async Task<IReadOnlyDictionary<Guid, CachedPayload>> ReadQuotesAsync(
        IReadOnlyCollection<Guid> instrumentIds, int ttlSeconds, CancellationToken cancel)
    {
        var cached = new Dictionary<Guid, CachedPayload>();
        if (instrumentIds.Count == 0) return cached;

        await using var cmd = db.CreateCommand(ReadQuotes);
        cmd.Parameters.Add(new() { Value = ttlSeconds, NpgsqlDbType = NpgsqlDbType.Integer });
        cmd.Parameters.Add(new() { Value = instrumentIds.ToArray() });
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        while (await reader.ReadAsync(cancel))
        {
            cached[reader.GetGuid(0)] = new CachedPayload(reader.GetString(1), reader.GetBoolean(2));
        }
        return cached;
    }

    public async Task WriteAsync(Guid instrumentId, string key, string json, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(Write);
        cmd.Parameters.Add(new() { Value = instrumentId });
        cmd.Parameters.Add(new() { Value = key });
        cmd.Parameters.Add(new() { Value = json });
        await cmd.ExecuteNonQueryAsync(cancel);
    }
}
