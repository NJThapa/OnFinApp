using OnFinApp.Api.Analyses;
using OnFinApp.Api.Instruments;
using OnFinApp.Api.Llm;
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

internal sealed class FakeAnalysisRepository : IAnalysisRepository
{
    public Dictionary<Guid, StoredAnalysis> Latest { get; } = [];
    public Dictionary<Guid, List<RatingRow>> Ratings { get; } = [];
    public List<(Guid JobId, Guid InstrumentId, GeneratedAnalysis Analysis, GenerationStats Stats, string GeneratedBy, int TtlSeconds)> Completed { get; } = [];

    /// False simulates another worker having taken the job over.
    public bool JobStillRunning { get; set; } = true;

    public Task<StoredAnalysis?> FindLatestAsync(Guid instrumentId, int minAgeSeconds, CancellationToken cancel) =>
        Task.FromResult(Latest.GetValueOrDefault(instrumentId));

    public Task<IReadOnlyDictionary<Guid, string>> LatestRatingsAsync(IReadOnlyCollection<Guid> instrumentIds, CancellationToken cancel) =>
        Task.FromResult<IReadOnlyDictionary<Guid, string>>(
            Latest.Where(e => instrumentIds.Contains(e.Key)).ToDictionary(e => e.Key, e => e.Value.Rating));

    public Task<IReadOnlyList<RatingRow>> ListRatingsAsync(Guid instrumentId, long beforeSeq, int limit, CancellationToken cancel) =>
        Task.FromResult<IReadOnlyList<RatingRow>>(Ratings.GetValueOrDefault(instrumentId, [])
            .Where(r => r.Seq < beforeSeq).OrderByDescending(r => r.Seq).Take(limit).ToList());

    public Task<bool> CompleteAsync(
        Guid jobId, Guid instrumentId, GeneratedAnalysis analysis, GenerationStats stats, string generatedBy, int ttlSeconds,
        CancellationToken cancel)
    {
        if (JobStillRunning) Completed.Add((jobId, instrumentId, analysis, stats, generatedBy, ttlSeconds));
        return Task.FromResult(JobStillRunning);
    }
}

internal sealed class FakeJobRepository : IGenerationJobRepository
{
    public JobState? LatestJob { get; set; }
    public RefreshOutcome RefreshOutcome { get; set; } = RefreshOutcome.Queued;
    public Queue<ClaimedJob> Claimable { get; } = new();
    public List<(Guid InstrumentId, string Trigger, Guid? UserId, string? CorrelationId)> Enqueued { get; } = [];
    public List<(Guid JobId, string Error, bool RefundAttempt)> Released { get; } = [];
    public List<(Guid JobId, string Error)> Failed { get; } = [];
    public int? LastRefreshLimit { get; private set; }

    public Task<JobState?> FindLatestAsync(Guid instrumentId, int cooldownSeconds, CancellationToken cancel) =>
        Task.FromResult(LatestJob);

    public Task<bool> EnqueueAsync(Guid instrumentId, string trigger, string? correlationId, CancellationToken cancel)
    {
        Enqueued.Add((instrumentId, trigger, null, correlationId));
        return Task.FromResult(true);
    }

    public Task<RefreshOutcome> EnqueueUserRefreshAsync(
        Guid userId, Guid instrumentId, int limit, string? correlationId, CancellationToken cancel)
    {
        LastRefreshLimit = limit;
        if (RefreshOutcome == RefreshOutcome.Queued) Enqueued.Add((instrumentId, JobTriggers.User, userId, correlationId));
        return Task.FromResult(RefreshOutcome);
    }

    public Task<ClaimedJob?> ClaimAsync(string claimedBy, int timeoutSeconds, CancellationToken cancel) =>
        Task.FromResult(Claimable.Count > 0 ? Claimable.Dequeue() : null);

    public Task ReleaseAsync(Guid jobId, string error, bool refundAttempt, CancellationToken cancel)
    {
        Released.Add((jobId, error, refundAttempt));
        return Task.CompletedTask;
    }

    public Task FailAsync(Guid jobId, string error, CancellationToken cancel)
    {
        Failed.Add((jobId, error));
        return Task.CompletedTask;
    }
}

/// Answers with <see cref="Answer"/>; set it to throw to simulate a failing LLM.
internal sealed class FakeLlm : ILlmClient
{
    public Func<LlmRequest, LlmResult> Answer { get; set; } = _ => SampleAnalysis.LlmResult();
    public List<LlmRequest> Requests { get; } = [];

    public Task<LlmResult> GenerateAsync(LlmRequest request, CancellationToken cancel)
    {
        cancel.ThrowIfCancellationRequested();
        Requests.Add(request);
        return Task.FromResult(Answer(request));
    }
}

internal static class SampleAnalysis
{
    public const string Url = "https://example.com/report";

    public const string Json = """
        {
          "summary": "A summary.",
          "bullPoints": ["Bull."],
          "bearPoints": ["Bear."],
          "risks": ["Risk."],
          "keyFigures": [{"label": "P/E ratio", "value": "30.1", "asOf": "2026-11-11"}],
          "rating": "BUY",
          "confidence": "MEDIUM",
          "rationale": "Because.",
          "sources": [{"title": "Report", "url": "https://example.com/report"}]
        }
        """;

    public static LlmResult LlmResult(string json = Json) =>
        new(json, [new Source("Report", Url), new Source("Other", "https://example.com/other")], 120, 340, "test-model");

    public static StoredAnalysis Stored(string rating = "HOLD", bool fresh = true, bool young = true) => new(
        new AnalysisContent("A summary.", ["Bull."], ["Bear."], ["Risk."], [new KeyFigure("P/E ratio", "30.1", "2026-11-11")]),
        [new Source("Report", Url)],
        rating, "MEDIUM", "Because.",
        new DateTime(2026, 11, 11, 17, 0, 0, DateTimeKind.Utc), new DateTime(2026, 11, 12, 17, 0, 0, DateTimeKind.Utc), "java",
        fresh, young);
}

/// The folder shared by both APIs (app/api/shared), found from the test's working directory.
internal static class SharedFolder
{
    public static string Path
    {
        get
        {
            for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
            {
                var candidate = System.IO.Path.Combine(dir.FullName, "shared");
                if (Directory.Exists(System.IO.Path.Combine(candidate, "llm"))) return candidate;
            }
            throw new DirectoryNotFoundException("Cannot find app/api/shared above the test directory.");
        }
    }
}
