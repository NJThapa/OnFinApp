using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using OnFinApp.Api.Analyses;
using OnFinApp.Api.Common;
using OnFinApp.Api.Llm;

namespace OnFinApp.Api.Tests;

/// Expected answers are the files in app/api/shared/llm/mock-examples, computed independently from the rules
/// in app/api/shared/llm/README.md. The Java API's MockLlmClientTest compares against the same files.
public class MockLlmClientTests
{
    private static readonly DateTimeOffset Wednesday = new(2026, 11, 11, 17, 0, 0, TimeSpan.Zero);

    private static MockLlmClient At(DateTimeOffset now, params string[] failSymbols) =>
        new(new FixedClock(now), TimeSpan.Zero, failSymbols.ToHashSet());

    private static LlmRequest Request(string symbol, string name, string type, string prompt = "A prompt of 28 characters.!!") =>
        new(prompt, "{}", WebSearch: true, new Dictionary<string, string>
        {
            ["symbol"] = symbol, ["name"] = name, ["type"] = type, ["exchange"] = "NYSE",
        });

    [Theory]
    [InlineData("AAPL")]
    [InlineData("SPY")]
    [InlineData("BRK.B")]
    public async Task Answers_match_the_shared_examples(string symbol)
    {
        var example = JsonNode.Parse(File.ReadAllText(Path.Combine(SharedFolder.Path, "llm", "mock-examples", $"{symbol}.json")))!;
        var instrument = example["instrument"]!;

        var result = await At(Wednesday).GenerateAsync(
            Request(symbol, (string)instrument["name"]!, (string)instrument["type"]!), default);

        Assert.True(JsonNode.DeepEquals(example["answer"], JsonNode.Parse(result.Json)), result.Json);
        Assert.True(JsonNode.DeepEquals(
            example["searchResults"], JsonSerializer.SerializeToNode(result.Sources, new JsonSerializerOptions(JsonSerializerDefaults.Web))));
        Assert.Equal((int)example["outputTokens"]!, result.OutputTokens);
        Assert.Equal(7, result.InputTokens); // 28 characters / 4
        Assert.Equal("mock", result.Model);
    }

    [Fact]
    public async Task The_rating_changes_from_day_to_day()
    {
        async Task<string> RatingAt(DateTimeOffset now)
        {
            var result = await At(now).GenerateAsync(Request("AAPL", "Apple Inc.", "STOCK"), default);
            return AnalysisParser.Parse(result).Rating;
        }

        Assert.Equal("HOLD", await RatingAt(Wednesday));
        Assert.Equal("SELL", await RatingAt(Wednesday.AddDays(1)));
        Assert.Equal("BUY", await RatingAt(Wednesday.AddDays(2)));
    }

    [Fact]
    public async Task The_day_is_the_New_York_calendar_day()
    {
        // 03:00 UTC on the 12th is still the 11th in New York.
        var result = await At(new DateTimeOffset(2026, 11, 12, 3, 0, 0, TimeSpan.Zero))
            .GenerateAsync(Request("AAPL", "Apple Inc.", "STOCK"), default);

        var analysis = AnalysisParser.Parse(result);
        Assert.Equal("HOLD", analysis.Rating);
        Assert.All(analysis.Content.KeyFigures, figure => Assert.Equal("2026-11-11", figure.AsOf));
    }

    [Fact]
    public async Task Fails_for_the_configured_tickers()
    {
        var error = await Assert.ThrowsAsync<LlmException>(() =>
            At(Wednesday, "AAPL").GenerateAsync(Request("AAPL", "Apple Inc.", "STOCK"), default));

        Assert.Equal("The mock LLM is set to fail for AAPL.", error.Message);
    }
}

public class PromptTemplateTests
{
    [Fact]
    public void The_shared_analysis_prompt_loads_and_renders()
    {
        var template = PromptTemplate.Load(SharedFolder.Path, "analysis");

        var prompt = template.Render(new Dictionary<string, string>
        {
            ["symbol"] = "AAPL", ["name"] = "Apple Inc.", ["type"] = "STOCK", ["exchange"] = "NASDAQ",
        });

        Assert.Contains("- Ticker: AAPL", prompt);
        Assert.Contains("- Name: Apple Inc.", prompt);
        Assert.DoesNotContain("{{", prompt);
        Assert.Equal("Analysis", (string)JsonNode.Parse(template.Schema)!["title"]!);
    }
}

public class AnalysisParserTests
{
    private static JsonObject Valid() => JsonNode.Parse(SampleAnalysis.Json)!.AsObject();

    private static GeneratedAnalysis Parse(JsonObject answer) => AnalysisParser.Parse(SampleAnalysis.LlmResult(answer.ToJsonString()));

    [Fact]
    public void A_valid_answer_is_accepted()
    {
        var analysis = Parse(Valid());

        Assert.Equal(("BUY", "MEDIUM", "Because."), (analysis.Rating, analysis.Confidence, analysis.Rationale));
        Assert.Equal("A summary.", analysis.Content.Summary);
        Assert.Equal(new KeyFigure("P/E ratio", "30.1", "2026-11-11"), Assert.Single(analysis.Content.KeyFigures));
        Assert.Equal(new Source("Report", SampleAnalysis.Url), Assert.Single(analysis.Sources));
    }

    [Fact]
    public void An_answer_may_have_no_key_figures()
    {
        var answer = Valid();
        answer["keyFigures"] = new JsonArray();

        Assert.Empty(Parse(answer).Content.KeyFigures);
    }

    public static TheoryData<string, Action<JsonObject>> InvalidAnswers => new()
    {
        { "summary is missing or blank.", a => a["summary"] = " " },
        { "bullPoints is missing or empty.", a => a.Remove("bullPoints") },
        { "bearPoints is missing or empty.", a => a["bearPoints"] = new JsonArray() },
        { "an entry of risks is missing or blank.", a => a["risks"] = new JsonArray("") },
        { "keyFigures is missing.", a => a.Remove("keyFigures") },
        { "a key figure has no asOf date written as YYYY-MM-DD.", a => a["keyFigures"]![0]!["asOf"] = "11 Nov 2026" },
        { "a key figure value is missing or blank.", a => a["keyFigures"]![0]!.AsObject().Remove("value") },
        { "rating must be BUY, HOLD or SELL.", a => a["rating"] = "STRONG BUY" },
        { "confidence must be LOW, MEDIUM or HIGH.", a => a.Remove("confidence") },
        { "rationale is missing or blank.", a => a["rationale"] = "" },
        { "it cites no sources.", a => a["sources"] = new JsonArray() },
        { "it cites no sources.", a => a.Remove("sources") },
        { "a source has no http(s) URL.", a => a["sources"]![0]!["url"] = "ftp://example.com/report" },
        { "it cites a source the search tool did not return.", a => a["sources"]![0]!["url"] = "https://example.com/invented" },
        { "a source title is missing or blank.", a => a["sources"]![0]!["title"] = "" },
        { "it is not JSON of the expected shape.", a => a["bullPoints"] = "not a list" },
    };

    [Theory]
    [MemberData(nameof(InvalidAnswers))]
    public void An_invalid_answer_is_rejected(string reason, Action<JsonObject> change)
    {
        var answer = Valid();
        change(answer);

        var error = Assert.Throws<InvalidAnalysisException>(() => Parse(answer));

        Assert.Equal("The LLM answer is not valid: " + reason, error.Message);
    }

    [Fact]
    public void Text_that_is_not_JSON_is_rejected()
    {
        Assert.Throws<InvalidAnalysisException>(() => AnalysisParser.Parse(SampleAnalysis.LlmResult("Sorry, I cannot help.")));
    }
}

public class RatingCursorTests
{
    [Fact]
    public void Round_trips()
    {
        Assert.Equal(42, RatingCursor.Decode(RatingCursor.Encode(42)));
    }

    [Fact]
    public void Encodes_as_base64url_of_the_sequence_number()
    {
        // Both APIs must produce the same cursor: base64url("42") without padding.
        Assert.Equal("NDI", RatingCursor.Encode(42));
    }

    [Theory]
    [InlineData("")]
    [InlineData("!!!")]
    [InlineData("QUFQTA")] // "AAPL"
    [InlineData("MA")] // "0"
    [InlineData("LTU")] // "-5"
    [InlineData("MTIzNDU2Nzg5MDEyMzQ1Njc4OQ")] // 19 digits
    public void Rejects_anything_else(string cursor)
    {
        Assert.Throws<InvalidCursorException>(() => RatingCursor.Decode(cursor));
    }
}

public class AnalysisServiceTests
{
    private static readonly Guid UserId = Guid.NewGuid();
    private static readonly Guid Apple = Sample.Apple.Id;

    private readonly FakeAnalysisRepository analyses = new();
    private readonly FakeJobRepository jobs = new();

    private AnalysisService Service() => new(
        new FakeInstrumentRepository(Sample.Apple),
        analyses,
        jobs,
        new WorkerSignal(),
        Options.Create(new AnalysisOptions { RefreshLimit = 3 }),
        NullLogger<AnalysisService>.Instance);

    [Fact]
    public async Task A_valid_analysis_is_returned_without_queueing_anything()
    {
        analyses.Latest[Apple] = SampleAnalysis.Stored(rating: "BUY");

        var result = await Service().GetAsync(" aapl ", "c-1", default);

        Assert.Equal(("AAPL", "READY"), (result.Symbol, result.Status));
        Assert.Equal("BUY", result.Analysis!.Rating);
        Assert.Equal("java", result.Analysis.GeneratedBy);
        Assert.Empty(jobs.Enqueued);
    }

    [Fact]
    public async Task With_no_analysis_a_generation_is_queued()
    {
        var result = await Service().GetAsync("AAPL", "c-1", default);

        Assert.Equal("GENERATING", result.Status);
        Assert.Null(result.Analysis);
        Assert.Equal([(Apple, "ON_DEMAND", (Guid?)null, "c-1")], jobs.Enqueued);
    }

    [Fact]
    public async Task An_expired_analysis_is_returned_while_its_replacement_is_generated()
    {
        analyses.Latest[Apple] = SampleAnalysis.Stored(fresh: false, young: false);
        jobs.LatestJob = new JobState("DONE", FinishedRecently: false);

        var result = await Service().GetAsync("AAPL", null, default);

        Assert.Equal("GENERATING", result.Status);
        Assert.NotNull(result.Analysis);
        Assert.Single(jobs.Enqueued);
    }

    [Theory]
    [InlineData("PENDING")]
    [InlineData("RUNNING")]
    public async Task While_a_job_is_queued_or_running_nothing_more_is_queued(string status)
    {
        analyses.Latest[Apple] = SampleAnalysis.Stored();
        jobs.LatestJob = new JobState(status, FinishedRecently: false);

        Assert.Equal("GENERATING", (await Service().GetAsync("AAPL", null, default)).Status);
        Assert.Equal("GENERATING", (await Service().RefreshAsync("AAPL", UserId, null, default)).Status);
        Assert.Empty(jobs.Enqueued);
    }

    [Fact]
    public async Task After_a_recent_failure_the_status_is_failed_and_nothing_is_queued()
    {
        analyses.Latest[Apple] = SampleAnalysis.Stored(fresh: false, young: false);
        jobs.LatestJob = new JobState("FAILED", FinishedRecently: true);

        var result = await Service().GetAsync("AAPL", null, default);

        Assert.Equal("FAILED", result.Status);
        Assert.NotNull(result.Analysis); // the last valid analysis stays visible
        Assert.Empty(jobs.Enqueued);
    }

    [Fact]
    public async Task After_the_cool_down_a_failed_generation_is_tried_again()
    {
        jobs.LatestJob = new JobState("FAILED", FinishedRecently: false);

        Assert.Equal("GENERATING", (await Service().GetAsync("AAPL", null, default)).Status);
        Assert.Single(jobs.Enqueued);
    }

    [Fact]
    public async Task A_refresh_is_ignored_while_the_analysis_is_young()
    {
        analyses.Latest[Apple] = SampleAnalysis.Stored(young: true);

        var result = await Service().RefreshAsync("AAPL", UserId, null, default);

        Assert.Equal("READY", result.Status);
        Assert.Empty(jobs.Enqueued);
    }

    [Fact]
    public async Task A_refresh_of_an_older_analysis_is_queued_for_the_user()
    {
        analyses.Latest[Apple] = SampleAnalysis.Stored(young: false);

        var result = await Service().RefreshAsync("AAPL", UserId, "c-2", default);

        Assert.Equal("GENERATING", result.Status);
        Assert.NotNull(result.Analysis);
        Assert.Equal([(Apple, "USER", (Guid?)UserId, "c-2")], jobs.Enqueued);
        Assert.Equal(3, jobs.LastRefreshLimit);
    }

    [Fact]
    public async Task A_refresh_at_the_users_limit_is_refused()
    {
        jobs.RefreshOutcome = RefreshOutcome.LimitReached;

        var error = await Assert.ThrowsAsync<RefreshLimitException>(() => Service().RefreshAsync("AAPL", UserId, null, default));

        Assert.Equal(429, error.Status);
        Assert.Equal("You can refresh up to 3 analyses in 24 hours.", error.Message);
    }

    [Fact]
    public async Task A_refresh_that_loses_the_race_to_another_request_joins_its_generation()
    {
        jobs.RefreshOutcome = RefreshOutcome.AlreadyQueued;

        Assert.Equal("GENERATING", (await Service().RefreshAsync("AAPL", UserId, null, default)).Status);
    }

    [Fact]
    public async Task Rating_history_is_paged_newest_first()
    {
        var at = new DateTime(2026, 11, 11, 17, 0, 0, DateTimeKind.Utc);
        analyses.Ratings[Apple] = [.. Enumerable.Range(1, 5).Select(seq => new RatingRow(seq, "HOLD", "LOW", $"Rationale {seq}", at.AddDays(seq)))];

        var first = await Service().GetRatingsAsync("aapl", null, 2, default);
        var second = await Service().GetRatingsAsync("aapl", first.NextCursor, 2, default);
        var last = await Service().GetRatingsAsync("aapl", second.NextCursor, 2, default);

        Assert.Equal(["Rationale 5", "Rationale 4"], first.Items.Select(i => i.Rationale));
        Assert.Equal(RatingCursor.Encode(4), first.NextCursor);
        Assert.Equal(["Rationale 3", "Rationale 2"], second.Items.Select(i => i.Rationale));
        Assert.Equal(["Rationale 1"], last.Items.Select(i => i.Rationale));
        Assert.Null(last.NextCursor);
        Assert.Equal(new RatingEntryResponse("HOLD", "LOW", "Rationale 5", at.AddDays(5)), first.Items[0]);
    }

    [Fact]
    public async Task Unknown_tickers_and_bad_cursors_are_refused()
    {
        await Assert.ThrowsAsync<InstrumentNotFoundException>(() => Service().GetAsync("NOPE", null, default));
        await Assert.ThrowsAsync<InstrumentNotFoundException>(() => Service().RefreshAsync("NOPE", UserId, null, default));
        await Assert.ThrowsAsync<InstrumentNotFoundException>(() => Service().GetRatingsAsync("NOPE", null, 20, default));
        await Assert.ThrowsAsync<InvalidCursorException>(() => Service().GetRatingsAsync("AAPL", "nonsense!", 20, default));
    }
}

public class AnalysisGeneratorTests
{
    private readonly FakeAnalysisRepository analyses = new();
    private readonly FakeJobRepository jobs = new();
    private readonly FakeLlm llm = new();

    private AnalysisGenerator Generator() => new(
        jobs, analyses, llm,
        new PromptTemplate("Analyse {{symbol}} ({{name}}), a {{type}} on {{exchange}}.", "{\"title\":\"Analysis\"}"),
        Options.Create(new AnalysisOptions { TtlHours = 6 }),
        NullLogger<AnalysisGenerator>.Instance);

    private ClaimedJob Claim(int attempts = 1)
    {
        var job = new ClaimedJob(Guid.NewGuid(), Sample.Apple, "ON_DEMAND", attempts, "c-1");
        jobs.Claimable.Enqueue(job);
        return job;
    }

    [Fact]
    public async Task With_no_job_there_is_nothing_to_do()
    {
        Assert.False(await Generator().ProcessNextAsync(default));
        Assert.Empty(llm.Requests);
    }

    [Fact]
    public async Task A_job_asks_the_llm_and_stores_the_checked_answer()
    {
        var job = Claim();

        Assert.True(await Generator().ProcessNextAsync(default));

        var request = Assert.Single(llm.Requests);
        Assert.Equal("Analyse AAPL (Apple Inc.), a STOCK on NASDAQ.", request.Prompt);
        Assert.Equal("{\"title\":\"Analysis\"}", request.Schema);
        Assert.True(request.WebSearch);

        var stored = Assert.Single(analyses.Completed);
        Assert.Equal((job.Id, Sample.Apple.Id, "dotnet", 6 * 3600), (stored.JobId, stored.InstrumentId, stored.GeneratedBy, stored.TtlSeconds));
        Assert.Equal("BUY", stored.Analysis.Rating);
        Assert.Equal(("test-model", 120, 340), (stored.Stats.Model, stored.Stats.InputTokens, stored.Stats.OutputTokens));
        Assert.Empty(jobs.Released);
        Assert.Empty(jobs.Failed);
    }

    [Fact]
    public async Task A_failed_first_attempt_goes_back_in_the_queue()
    {
        var job = Claim(attempts: 1);
        llm.Answer = _ => throw new LlmException("The vendor is down.");

        await Generator().ProcessNextAsync(default);

        Assert.Equal([(job.Id, "The vendor is down.", false)], jobs.Released);
        Assert.Empty(jobs.Failed);
        Assert.Empty(analyses.Completed);
    }

    [Fact]
    public async Task A_failed_retry_marks_the_job_failed()
    {
        var job = Claim(attempts: 2);
        llm.Answer = _ => throw new LlmException("The vendor is down.");

        await Generator().ProcessNextAsync(default);

        Assert.Equal([(job.Id, "The vendor is down.")], jobs.Failed);
        Assert.Empty(jobs.Released);
    }

    [Fact]
    public async Task An_answer_without_sources_is_not_stored()
    {
        var job = Claim(attempts: 2);
        llm.Answer = _ => SampleAnalysis.LlmResult(SampleAnalysis.Json.Replace(
            "[{\"title\": \"Report\", \"url\": \"https://example.com/report\"}]", "[]"));

        await Generator().ProcessNextAsync(default);

        Assert.Equal([(job.Id, "The LLM answer is not valid: it cites no sources.")], jobs.Failed);
        Assert.Empty(analyses.Completed);
    }

    [Fact]
    public async Task A_job_abandoned_on_its_last_attempt_is_failed_without_asking_the_llm()
    {
        var job = Claim(attempts: 3);

        await Generator().ProcessNextAsync(default);

        Assert.Equal([(job.Id, "The job did not finish in time.")], jobs.Failed);
        Assert.Empty(llm.Requests);
    }

    [Fact]
    public async Task On_shutdown_the_job_is_handed_back_without_using_up_an_attempt()
    {
        var job = Claim();
        using var stop = new CancellationTokenSource();
        llm.Answer = _ =>
        {
            stop.Cancel();
            throw new OperationCanceledException(stop.Token);
        };

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Generator().ProcessNextAsync(stop.Token));

        Assert.Equal([(job.Id, "The API shut down before the job finished.", true)], jobs.Released);
    }

    [Fact]
    public async Task A_result_for_a_job_another_worker_took_over_is_dropped()
    {
        Claim();
        analyses.JobStillRunning = false;

        Assert.True(await Generator().ProcessNextAsync(default));

        Assert.Empty(analyses.Completed);
        Assert.Empty(jobs.Failed);
    }

    [Fact]
    public async Task Long_errors_are_shortened_before_they_are_stored()
    {
        Claim(attempts: 2);
        llm.Answer = _ => throw new LlmException(new string('x', 2000));

        await Generator().ProcessNextAsync(default);

        Assert.Equal(500, Assert.Single(jobs.Failed).Error.Length);
    }
}
