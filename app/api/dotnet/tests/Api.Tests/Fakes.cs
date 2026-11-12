using OnFinApp.Api.Instruments;
using OnFinApp.Api.Prices;
using OnFinApp.Api.Watchlist;

namespace OnFinApp.Api.Tests;

/// A clock that always shows the same moment.
internal sealed class FixedClock(DateTimeOffset now) : TimeProvider
{
    public override DateTimeOffset GetUtcNow() => now;
}

internal sealed class FakePriceCache : IPriceCacheRepository
{
    public Dictionary<(Guid Id, string Key), CachedPayload> Entries { get; } = [];
    public List<(Guid Id, string Key)> Writes { get; } = [];

    public Task<CachedPayload?> ReadAsync(Guid instrumentId, string key, int? ttlSeconds, CancellationToken cancel) =>
        Task.FromResult(Entries.GetValueOrDefault((instrumentId, key)));

    public Task<IReadOnlyDictionary<Guid, CachedPayload>> ReadQuotesAsync(
        IReadOnlyCollection<Guid> instrumentIds, int ttlSeconds, CancellationToken cancel) =>
        Task.FromResult<IReadOnlyDictionary<Guid, CachedPayload>>(Entries
            .Where(e => e.Key.Key == IPriceCacheRepository.QuoteKey && instrumentIds.Contains(e.Key.Id))
            .ToDictionary(e => e.Key.Id, e => e.Value));

    public Task WriteAsync(Guid instrumentId, string key, string json, CancellationToken cancel)
    {
        Writes.Add((instrumentId, key));
        Entries[(instrumentId, key)] = new CachedPayload(json, Fresh: true);
        return Task.CompletedTask;
    }
}

/// Answers with fixed data, or fails when <see cref="Down"/> is set.
internal sealed class FakePriceProvider : IPriceProvider
{
    public static readonly Quote Quote = new(10, 1, 11.11, 9, new DateTime(2026, 11, 11, 17, 0, 0, DateTimeKind.Utc));
    public static readonly IReadOnlyList<PricePoint> Points = [new(new DateTime(2026, 11, 11, 21, 0, 0, DateTimeKind.Utc), 10)];

    public bool Down { get; set; }
    public int Calls { get; private set; }

    public Task<IReadOnlyList<ProviderInstrument>> ListInstrumentsAsync(CancellationToken cancel) =>
        Task.FromResult<IReadOnlyList<ProviderInstrument>>([]);

    public Task<Quote> GetQuoteAsync(string symbol, CancellationToken cancel) => Answer(Quote);

    public Task<IReadOnlyList<PricePoint>> GetCandlesAsync(string symbol, string range, CancellationToken cancel) => Answer(Points);

    private Task<T> Answer<T>(T value)
    {
        Calls++;
        return Down ? Task.FromException<T>(new PriceProviderException("down")) : Task.FromResult(value);
    }
}

internal sealed class FakeInstrumentRepository(params Instrument[] instruments) : IInstrumentRepository
{
    public (string Query, InstrumentCursor After, int Limit)? LastSearch { get; private set; }

    public Task<Instrument?> FindAsync(string symbol, CancellationToken cancel) =>
        Task.FromResult(instruments.FirstOrDefault(i => i.Symbol == symbol));

    /// Returns every instrument as a rank-2 match, in the order given, up to the limit.
    public Task<IReadOnlyList<InstrumentMatch>> SearchAsync(string query, InstrumentCursor after, int limit, CancellationToken cancel)
    {
        LastSearch = (query, after, limit);
        return Task.FromResult<IReadOnlyList<InstrumentMatch>>(
            instruments.Take(limit).Select(i => new InstrumentMatch(i, 2)).ToList());
    }

    public Task UpsertAsync(IReadOnlyList<ProviderInstrument> list, CancellationToken cancel) => Task.CompletedTask;
}

internal sealed class FakeWatchlistRepository : IWatchlistRepository
{
    public List<(Guid UserId, Instrument Instrument)> Rows { get; } = [];
    private readonly Dictionary<Guid, Instrument> known = [];

    public FakeWatchlistRepository(params Instrument[] instruments)
    {
        foreach (var i in instruments) known[i.Id] = i;
    }

    public Task<IReadOnlyList<Instrument>> ListAsync(Guid userId, CancellationToken cancel) =>
        Task.FromResult<IReadOnlyList<Instrument>>(Rows.Where(r => r.UserId == userId).Select(r => r.Instrument).ToList());

    public Task<bool> IsFollowingAsync(Guid userId, Guid instrumentId, CancellationToken cancel) =>
        Task.FromResult(Rows.Any(r => r.UserId == userId && r.Instrument.Id == instrumentId));

    public Task<FollowOutcome> FollowAsync(Guid userId, Guid instrumentId, int limit, CancellationToken cancel)
    {
        if (Rows.Any(r => r.UserId == userId && r.Instrument.Id == instrumentId)) return Task.FromResult(FollowOutcome.AlreadyFollowing);
        if (Rows.Count(r => r.UserId == userId) >= limit) return Task.FromResult(FollowOutcome.LimitReached);
        Rows.Add((userId, known[instrumentId]));
        return Task.FromResult(FollowOutcome.Followed);
    }

    public Task UnfollowAsync(Guid userId, Guid instrumentId, CancellationToken cancel)
    {
        Rows.RemoveAll(r => r.UserId == userId && r.Instrument.Id == instrumentId);
        return Task.CompletedTask;
    }
}

internal static class Sample
{
    public static readonly Instrument Apple = new(Guid.NewGuid(), "AAPL", "Apple Inc.", "STOCK", "NASDAQ");
    public static readonly Instrument Microsoft = new(Guid.NewGuid(), "MSFT", "Microsoft Corporation", "STOCK", "NASDAQ");
    public static readonly Instrument Spy = new(Guid.NewGuid(), "SPY", "SPDR S&P 500 ETF Trust", "ETF", "NYSE Arca");
}
