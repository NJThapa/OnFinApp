package com.onfinapp.api.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.onfinapp.api.analysis.AnalysisModels.GeneratedAnalysis;
import com.onfinapp.api.analysis.AnalysisModels.GenerationStats;
import com.onfinapp.api.analysis.GenerationJobRepository.ClaimedJob;
import com.onfinapp.api.common.CorrelationFilter;
import com.onfinapp.api.llm.LlmClient;
import com.onfinapp.api.llm.LlmException;
import com.onfinapp.api.llm.LlmRequest;
import com.onfinapp.api.llm.LlmResult;
import com.onfinapp.api.llm.PromptTemplate;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * Runs generation jobs: asks the LLM for an analysis, checks the answer and stores it with its rating.
 * A failed or invalid answer is retried once; after that the job is marked failed and the last valid
 * analysis stays in place.
 */
@Component
public class AnalysisGenerator {

    /** Recorded as generated_by and claimed_by. */
    public static final String STACK = "java";

    /** The first try plus one retry. */
    public static final int MAX_ATTEMPTS = 2;

    private static final int MAX_ERROR_LENGTH = 500;
    private static final String JOB_LOG_PROPERTY = "jobId";
    private static final Logger log = LoggerFactory.getLogger(AnalysisGenerator.class);

    /**
     * A context whose parent span is marked "not sampled". Database calls made inside it are not traced,
     * because the tracer follows the parent's decision.
     */
    private static final Context NOT_TRACED = Context.root().with(Span.wrap(SpanContext.create(
            "00000000000000000000000000000001", "0000000000000001", TraceFlags.getDefault(), TraceState.getDefault())));

    private final GenerationJobRepository jobs;
    private final AnalysisRepository analyses;
    private final LlmClient llm;
    private final PromptTemplate prompt;
    private final AnalysisProperties properties;

    public AnalysisGenerator(GenerationJobRepository jobs, AnalysisRepository analyses, LlmClient llm,
            PromptTemplate prompt, AnalysisProperties properties) {
        this.jobs = jobs;
        this.analyses = analyses;
        this.llm = llm;
        this.prompt = prompt;
        this.properties = properties;
    }

    /**
     * Claims the next job and runs it.
     *
     * @return false when there was nothing to do
     */
    public boolean processNext() {
        Optional<ClaimedJob> claimed;
        // Looking for work happens every few seconds; keep it out of the traces.
        try (Scope ignored = NOT_TRACED.makeCurrent()) {
            claimed = jobs.claim(STACK, properties.jobTimeoutSeconds());
        }
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedJob job = claimed.get();

        Span span = GlobalOpenTelemetry.getTracer("com.onfinapp.api").spanBuilder("analysis.generate")
                .setNoParent()
                .setAttribute("app.job_id", job.id().toString())
                .setAttribute("app.symbol", job.instrument().symbol())
                .startSpan();
        // The correlation ID of the request that queued the job ties these lines to that request.
        if (job.correlationId() != null) {
            span.setAttribute(CorrelationFilter.SPAN_ATTRIBUTE, job.correlationId());
            MDC.put(CorrelationFilter.LOG_PROPERTY, job.correlationId());
        }
        MDC.put(JOB_LOG_PROPERTY, job.id().toString());
        try (Scope ignored = span.makeCurrent()) {
            run(job);
        } finally {
            span.end();
            MDC.remove(JOB_LOG_PROPERTY);
            MDC.remove(CorrelationFilter.LOG_PROPERTY);
        }
        return true;
    }

    private void run(ClaimedJob job) {
        String symbol = job.instrument().symbol();
        if (job.attempts() > MAX_ATTEMPTS) {
            // Claimed again after timing out on its last allowed attempt.
            log.error("Analysis of {} failed: job {} was abandoned {} times", symbol, job.id(), job.attempts() - 1);
            jobs.fail(job.id(), "The job did not finish in time.");
            return;
        }

        try {
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("symbol", symbol);
            variables.put("name", job.instrument().name());
            variables.put("type", job.instrument().type());
            variables.put("exchange", job.instrument().exchange());

            long started = System.nanoTime();
            LlmResult result = llm.generate(new LlmRequest(prompt.render(variables), prompt.schema(), true, variables));
            GenerationStats stats = new GenerationStats(result.model(),
                    (int) ((System.nanoTime() - started) / 1_000_000), result.inputTokens(), result.outputTokens());

            GeneratedAnalysis analysis = AnalysisParser.parse(result);
            boolean stored = analyses.complete(
                    job.id(), job.instrument().id(), analysis, stats, STACK, properties.ttlHours() * 3600);
            if (!stored) {
                log.warn("Analysis of {} discarded: job {} was taken over by another worker", symbol, job.id());
                return;
            }
            log.info("Generated the analysis of {}: job {}, trigger {}, attempt {}, api {}, model {}, "
                    + "{} ms, {} input and {} output tokens, rating {}",
                    symbol, job.id(), job.trigger(), job.attempts(), STACK, stats.model(), stats.durationMs(),
                    stats.inputTokens(), stats.outputTokens(), analysis.rating());
        } catch (LlmException | InvalidAnalysisException e) {
            // Thread.interrupted() also clears the flag, so the database call below is not cut short.
            if (Thread.interrupted()) {
                // The API is shutting down: hand the job back so another instance can run it straight away.
                jobs.release(job.id(), "The API shut down before the job finished.", true);
                Thread.currentThread().interrupt();
                return;
            }
            String error = e.getMessage().length() > MAX_ERROR_LENGTH
                    ? e.getMessage().substring(0, MAX_ERROR_LENGTH)
                    : e.getMessage();
            if (job.attempts() < MAX_ATTEMPTS) {
                log.warn("Analysis of {} failed on attempt {} of job {}; it will be retried: {}",
                        symbol, job.attempts(), job.id(), error);
                jobs.release(job.id(), error, false);
            } else {
                log.error("Analysis of {} failed on attempt {} of job {}; giving up: {}",
                        symbol, job.attempts(), job.id(), error);
                jobs.fail(job.id(), error);
            }
        }
    }
}
