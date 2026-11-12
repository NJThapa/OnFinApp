using Microsoft.Extensions.Options;
using OnFinApp.Api.Common;
using OnFinApp.Api.Instruments;

namespace OnFinApp.Api.Analyses;

/// Analysis settings. Bound from the ANALYSIS_* environment variables.
public sealed class AnalysisOptions
{
    /// How long an analysis is valid (ANALYSIS_TTL_HOURS).
    public int TtlHours { get; set; } = 24;

    /// Refreshes one user can queue in any 24 hours (ANALYSIS_REFRESH_LIMIT).
    public int RefreshLimit { get; set; } = 5;

    /// A refresh is ignored while the current analysis is younger than this (ANALYSIS_REFRESH_MIN_AGE_MINUTES).
    public int RefreshMinAgeMinutes { get; set; } = 60;

    /// After a failed generation, no new one is started on demand for this long (ANALYSIS_RETRY_AFTER_MINUTES).
    public int RetryAfterMinutes { get; set; } = 5;

    /// Whether this API runs generation jobs (ANALYSIS_WORKER_ENABLED).
    public bool WorkerEnabled { get; set; } = true;

    /// How often the worker looks for jobs queued by the other API (ANALYSIS_WORKER_POLL_MS).
    public int WorkerPollMs { get; set; } = 2000;

    /// A job running longer than this is taken to be abandoned and is claimed again (ANALYSIS_JOB_TIMEOUT_SECONDS).
    public int JobTimeoutSeconds { get; set; } = 300;
}

/// Lets a request wake the worker as soon as it queues a job, instead of waiting for the next poll.
public sealed class WorkerSignal
{
    private readonly SemaphoreSlim pending = new(0, 1);

    public void Notify()
    {
        try
        {
            pending.Release();
        }
        catch (SemaphoreFullException)
        {
            // already signalled
        }
    }

    /// Returns when signalled or after the timeout, whichever comes first.
    public Task WaitAsync(TimeSpan timeout, CancellationToken cancel) => pending.WaitAsync(timeout, cancel);
}

/// Reads analyses and ratings and decides when a new generation is queued. Generation itself runs in
/// <see cref="AnalysisGenerator"/>, never inside a request.
public sealed class AnalysisService(
    IInstrumentRepository instruments,
    IAnalysisRepository analyses,
    IGenerationJobRepository jobs,
    WorkerSignal worker,
    IOptions<AnalysisOptions> options,
    ILogger<AnalysisService> log)
{
    private AnalysisOptions Options => options.Value;

    /// The stored analysis; queues a generation when there is none or it has expired.
    public async Task<AnalysisStatusResponse> GetAsync(string symbol, string? correlationId, CancellationToken cancel)
    {
        var instrument = await RequireAsync(symbol, cancel);
        var latest = await FindLatestAsync(instrument, cancel);
        var job = await jobs.FindLatestAsync(instrument.Id, Options.RetryAfterMinutes * 60, cancel);

        if (job is { Active: true }) return AnalysisStatusResponse.Of(instrument, AnalysisStatuses.Generating, latest);
        if (job is { FailedRecently: true }) return AnalysisStatusResponse.Of(instrument, AnalysisStatuses.Failed, latest);
        if (latest is { Fresh: true }) return AnalysisStatusResponse.Of(instrument, AnalysisStatuses.Ready, latest);

        if (await jobs.EnqueueAsync(instrument.Id, JobTriggers.OnDemand, correlationId, cancel))
        {
            log.LogInformation("Queued an analysis of {Symbol}: {Reason}", instrument.Symbol, latest is null ? "none exists" : "the last one expired");
            worker.Notify();
        }
        return AnalysisStatusResponse.Of(instrument, AnalysisStatuses.Generating, latest);
    }

    /// A user's request for a new analysis. Throws <see cref="RefreshLimitException"/> at the user's limit.
    public async Task<AnalysisStatusResponse> RefreshAsync(string symbol, Guid userId, string? correlationId, CancellationToken cancel)
    {
        var instrument = await RequireAsync(symbol, cancel);
        var latest = await FindLatestAsync(instrument, cancel);
        var job = await jobs.FindLatestAsync(instrument.Id, Options.RetryAfterMinutes * 60, cancel);

        if (job is { Active: true }) return AnalysisStatusResponse.Of(instrument, AnalysisStatuses.Generating, latest);
        if (latest is { Young: true })
        {
            log.LogInformation("Refresh of {Symbol} by user {UserId} ignored: the analysis is younger than {Minutes} minutes",
                instrument.Symbol, userId, Options.RefreshMinAgeMinutes);
            return AnalysisStatusResponse.Of(instrument, AnalysisStatuses.Ready, latest);
        }

        var outcome = await jobs.EnqueueUserRefreshAsync(userId, instrument.Id, Options.RefreshLimit, correlationId, cancel);
        if (outcome == RefreshOutcome.LimitReached)
        {
            log.LogInformation("User {UserId} is at the refresh limit of {Limit}; {Symbol} not refreshed", userId, Options.RefreshLimit, instrument.Symbol);
            throw new RefreshLimitException(Options.RefreshLimit);
        }
        if (outcome == RefreshOutcome.Queued)
        {
            log.LogInformation("User {UserId} queued a refresh of the {Symbol} analysis", userId, instrument.Symbol);
            worker.Notify();
        }
        return AnalysisStatusResponse.Of(instrument, AnalysisStatuses.Generating, latest);
    }

    public async Task<RatingPageResponse> GetRatingsAsync(string symbol, string? cursor, int limit, CancellationToken cancel)
    {
        var before = string.IsNullOrEmpty(cursor) ? RatingCursor.Start : RatingCursor.Decode(cursor);
        var instrument = await RequireAsync(symbol, cancel);

        // One extra row tells whether there is a next page.
        var rows = await analyses.ListRatingsAsync(instrument.Id, before, limit + 1, cancel);
        var page = rows.Take(limit).ToList();
        return new RatingPageResponse(
            page.Select(r => new RatingEntryResponse(r.Rating, r.Confidence, r.Rationale, r.CreatedAt)).ToList(),
            rows.Count > limit ? RatingCursor.Encode(page[^1].Seq) : null);
    }

    private Task<StoredAnalysis?> FindLatestAsync(Instrument instrument, CancellationToken cancel) =>
        analyses.FindLatestAsync(instrument.Id, Options.RefreshMinAgeMinutes * 60, cancel);

    private async Task<Instrument> RequireAsync(string symbol, CancellationToken cancel)
    {
        var normalized = InstrumentService.Normalize(symbol);
        return await instruments.FindAsync(normalized, cancel) ?? throw new InstrumentNotFoundException(normalized);
    }
}
