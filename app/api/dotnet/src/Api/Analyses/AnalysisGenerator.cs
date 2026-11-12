using System.Diagnostics;
using Microsoft.Extensions.Options;
using OnFinApp.Api.Common;
using OnFinApp.Api.Llm;
using OpenTelemetry;

namespace OnFinApp.Api.Analyses;

/// Runs generation jobs: asks the LLM for an analysis, checks the answer and stores it with its rating.
/// A failed or invalid answer is retried once; after that the job is marked failed and the last valid
/// analysis stays in place.
public sealed class AnalysisGenerator(
    IGenerationJobRepository jobs,
    IAnalysisRepository analyses,
    ILlmClient llm,
    PromptTemplate prompt,
    IOptions<AnalysisOptions> options,
    ILogger<AnalysisGenerator> log)
{
    /// Recorded as generated_by and claimed_by.
    public const string Stack = "dotnet";

    /// The first try plus one retry.
    public const int MaxAttempts = 2;

    private const int MaxErrorLength = 500;

    /// Claims the next job and runs it. False when there was nothing to do.
    public async Task<bool> ProcessNextAsync(CancellationToken cancel)
    {
        ClaimedJob? job;
        // Looking for work happens every few seconds; keep it out of the traces.
        using (SuppressInstrumentationScope.Begin())
        {
            job = await jobs.ClaimAsync(Stack, options.Value.JobTimeoutSeconds, cancel);
        }
        if (job is null) return false;

        using var activity = TelemetrySetup.Source.StartActivity("analysis.generate");
        activity?.SetTag("app.job_id", job.Id.ToString());
        activity?.SetTag("app.symbol", job.Instrument.Symbol);
        if (job.CorrelationId is not null) activity?.SetTag(CorrelationMiddleware.SpanAttribute, job.CorrelationId);

        // The correlation ID of the request that queued the job ties these lines to that request.
        using (job.CorrelationId is null ? null : log.BeginScope("CorrelationId:{CorrelationId}", job.CorrelationId))
        using (log.BeginScope("JobId:{JobId}", job.Id))
        {
            await RunAsync(job, cancel);
        }
        return true;
    }

    private async Task RunAsync(ClaimedJob job, CancellationToken cancel)
    {
        var symbol = job.Instrument.Symbol;
        if (job.Attempts > MaxAttempts)
        {
            // Claimed again after timing out on its last allowed attempt.
            log.LogError("Analysis of {Symbol} failed: job {JobId} was abandoned {Attempts} times", symbol, job.Id, job.Attempts - 1);
            await jobs.FailAsync(job.Id, "The job did not finish in time.", cancel);
            return;
        }

        try
        {
            var variables = new Dictionary<string, string>
            {
                ["symbol"] = symbol,
                ["name"] = job.Instrument.Name,
                ["type"] = job.Instrument.Type,
                ["exchange"] = job.Instrument.Exchange,
            };
            var started = Stopwatch.GetTimestamp();
            var result = await llm.GenerateAsync(new LlmRequest(prompt.Render(variables), prompt.Schema, WebSearch: true, variables), cancel);
            var stats = new GenerationStats(
                result.Model, (int)Stopwatch.GetElapsedTime(started).TotalMilliseconds, result.InputTokens, result.OutputTokens);

            var analysis = AnalysisParser.Parse(result);
            var stored = await analyses.CompleteAsync(
                job.Id, job.Instrument.Id, analysis, stats, Stack, options.Value.TtlHours * 3600, cancel);
            if (!stored)
            {
                log.LogWarning("Analysis of {Symbol} discarded: job {JobId} was taken over by another worker", symbol, job.Id);
                return;
            }
            log.LogInformation(
                "Generated the analysis of {Symbol}: job {JobId}, trigger {Trigger}, attempt {Attempt}, api {Stack}, model {Model}, "
                + "{DurationMs} ms, {InputTokens} input and {OutputTokens} output tokens, rating {Rating}",
                symbol, job.Id, job.Trigger, job.Attempts, Stack, stats.Model, stats.DurationMs, stats.InputTokens, stats.OutputTokens,
                analysis.Rating);
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            // The API is shutting down: hand the job back so another instance can run it straight away.
            await jobs.ReleaseAsync(job.Id, "The API shut down before the job finished.", refundAttempt: true, CancellationToken.None);
            throw;
        }
        catch (Exception e) when (e is LlmException or InvalidAnalysisException)
        {
            var error = e.Message.Length > MaxErrorLength ? e.Message[..MaxErrorLength] : e.Message;
            if (job.Attempts < MaxAttempts)
            {
                log.LogWarning("Analysis of {Symbol} failed on attempt {Attempt} of job {JobId}; it will be retried: {Error}",
                    symbol, job.Attempts, job.Id, error);
                await jobs.ReleaseAsync(job.Id, error, refundAttempt: false, cancel);
            }
            else
            {
                log.LogError("Analysis of {Symbol} failed on attempt {Attempt} of job {JobId}; giving up: {Error}",
                    symbol, job.Attempts, job.Id, error);
                await jobs.FailAsync(job.Id, error, cancel);
            }
        }
    }
}

/// Background loop that runs generation jobs one at a time.
public sealed class AnalysisWorker(
    AnalysisGenerator generator, WorkerSignal signal, IOptions<AnalysisOptions> options, ILogger<AnalysisWorker> log)
    : BackgroundService
{
    private static readonly TimeSpan ErrorDelay = TimeSpan.FromSeconds(10);

    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        if (!options.Value.WorkerEnabled)
        {
            log.LogInformation("The analysis worker is disabled; this API queues jobs but does not run them");
            return;
        }

        var poll = TimeSpan.FromMilliseconds(options.Value.WorkerPollMs);
        log.LogInformation("The analysis worker is running; it looks for jobs every {PollMs} ms", options.Value.WorkerPollMs);
        while (!stop.IsCancellationRequested)
        {
            try
            {
                if (!await generator.ProcessNextAsync(stop)) await signal.WaitAsync(poll, stop);
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e)
            {
                // Usually the database being away: wait, then carry on.
                log.LogWarning("The analysis worker hit an error and will try again in {Seconds} s: {Error}", ErrorDelay.TotalSeconds, e.Message);
                try
                {
                    await Task.Delay(ErrorDelay, stop);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
            }
        }
    }
}
