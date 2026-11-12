using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.Extensions.Options;
using OnFinApp.Api.Common;
using OnFinApp.Api.Llm;
using OnFinApp.Api.Users;

namespace OnFinApp.Api.Analyses;

public static class AnalysisEndpoints
{
    private const int DefaultLimit = 20;
    private const int MaxLimit = 50;

    public static IServiceCollection AddAnalyses(this IServiceCollection services, IConfiguration config)
    {
        services.AddOptions<AnalysisOptions>()
            .Configure(options =>
            {
                options.TtlHours = config.GetValue("ANALYSIS_TTL_HOURS", options.TtlHours);
                options.RefreshLimit = config.GetValue("ANALYSIS_REFRESH_LIMIT", options.RefreshLimit);
                options.RefreshMinAgeMinutes = config.GetValue("ANALYSIS_REFRESH_MIN_AGE_MINUTES", options.RefreshMinAgeMinutes);
                options.RetryAfterMinutes = config.GetValue("ANALYSIS_RETRY_AFTER_MINUTES", options.RetryAfterMinutes);
                options.WorkerEnabled = config.GetValue("ANALYSIS_WORKER_ENABLED", options.WorkerEnabled);
                options.WorkerPollMs = config.GetValue("ANALYSIS_WORKER_POLL_MS", options.WorkerPollMs);
                options.JobTimeoutSeconds = config.GetValue("ANALYSIS_JOB_TIMEOUT_SECONDS", options.JobTimeoutSeconds);
            })
            .Validate(o => o.TtlHours > 0, "ANALYSIS_TTL_HOURS must be positive.")
            .Validate(o => o.RefreshLimit >= 0, "ANALYSIS_REFRESH_LIMIT must not be negative.")
            .Validate(o => o.RefreshMinAgeMinutes >= 0 && o.RefreshMinAgeMinutes <= o.TtlHours * 60,
                "ANALYSIS_REFRESH_MIN_AGE_MINUTES must be between 0 and the analysis lifetime.")
            .Validate(o => o.RetryAfterMinutes >= 0, "ANALYSIS_RETRY_AFTER_MINUTES must not be negative.")
            .Validate(o => o.WorkerPollMs > 0, "ANALYSIS_WORKER_POLL_MS must be positive.")
            .Validate(o => o.JobTimeoutSeconds > 0, "ANALYSIS_JOB_TIMEOUT_SECONDS must be positive.")
            .ValidateOnStart();

        return services
            .AddSingleton(provider =>
                PromptTemplate.Load(provider.GetRequiredService<IOptions<LlmOptions>>().Value.SharedDir, "analysis"))
            .AddSingleton<IAnalysisRepository, AnalysisRepository>()
            .AddSingleton<IGenerationJobRepository, GenerationJobRepository>()
            .AddSingleton<WorkerSignal>()
            .AddSingleton<AnalysisService>()
            .AddSingleton<AnalysisGenerator>()
            .AddHostedService<AnalysisWorker>();
    }

    public static void MapAnalyses(this RouteGroupBuilder v1)
    {
        var group = v1.MapGroup("/instruments/{symbol}");
        group.MapGet("/analysis", Get);
        group.MapPost("/analysis/refresh", Refresh);
        group.MapGet("/ratings", GetRatings);
    }

    private static async Task<Results<Ok<AnalysisStatusResponse>, Accepted<AnalysisStatusResponse>>> Get(
        string symbol, HttpContext context, AnalysisService analyses, CancellationToken cancel) =>
        Respond(await analyses.GetAsync(symbol, CorrelationMiddleware.IdOf(context), cancel));

    private static async Task<Results<Ok<AnalysisStatusResponse>, Accepted<AnalysisStatusResponse>>> Refresh(
        string symbol, HttpContext context, ClaimsPrincipal principal, AnalysisService analyses, UserService users,
        CancellationToken cancel)
    {
        var user = await users.ResolveAsync(principal, cancel);
        return Respond(await analyses.RefreshAsync(symbol, user.Id, CorrelationMiddleware.IdOf(context), cancel));
    }

    private static async Task<Ok<RatingPageResponse>> GetRatings(
        string symbol,
        string? cursor,
        [Range(1, MaxLimit, ErrorMessage = "limit must be a whole number from 1 to 50.")] int? limit,
        AnalysisService analyses,
        CancellationToken cancel) =>
        TypedResults.Ok(await analyses.GetRatingsAsync(symbol, cursor, limit ?? DefaultLimit, cancel));

    /// 202 while a generation is queued or running, otherwise 200.
    private static Results<Ok<AnalysisStatusResponse>, Accepted<AnalysisStatusResponse>> Respond(AnalysisStatusResponse body) =>
        body.Status == AnalysisStatuses.Generating ? TypedResults.Accepted((string?)null, body) : TypedResults.Ok(body);
}
