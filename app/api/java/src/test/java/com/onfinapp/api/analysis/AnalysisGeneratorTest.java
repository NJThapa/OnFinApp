package com.onfinapp.api.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.onfinapp.api.analysis.AnalysisModels.GeneratedAnalysis;
import com.onfinapp.api.analysis.AnalysisModels.GenerationStats;
import com.onfinapp.api.analysis.GenerationJobRepository.ClaimedJob;
import com.onfinapp.api.instrument.Instrument;
import com.onfinapp.api.llm.LlmClient;
import com.onfinapp.api.llm.LlmException;
import com.onfinapp.api.llm.LlmRequest;
import com.onfinapp.api.llm.PromptTemplate;

class AnalysisGeneratorTest {

    private static final Instrument APPLE = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "STOCK", "NASDAQ");
    private static final int TIMEOUT_SECONDS = 300;

    private final GenerationJobRepository jobs = mock(GenerationJobRepository.class);
    private final AnalysisRepository analyses = mock(AnalysisRepository.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final AnalysisGenerator generator = new AnalysisGenerator(jobs, analyses, llm,
            new PromptTemplate("Analyse {{symbol}} ({{name}}), a {{type}} on {{exchange}}.", "{\"title\":\"Analysis\"}"),
            new AnalysisProperties(6, 5, 60, 5, true, 2000, TIMEOUT_SECONDS));

    @BeforeEach
    void defaults() {
        when(jobs.claim("java", TIMEOUT_SECONDS)).thenReturn(Optional.empty());
        when(llm.generate(any())).thenReturn(SampleAnalysis.llmResult(SampleAnalysis.JSON));
        when(analyses.complete(any(), any(), any(), any(), anyString(), anyInt())).thenReturn(true);
    }

    private ClaimedJob claim(int attempts) {
        ClaimedJob job = new ClaimedJob(UUID.randomUUID(), APPLE, "ON_DEMAND", attempts, "c-1");
        when(jobs.claim("java", TIMEOUT_SECONDS)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void withNoJobThereIsNothingToDo() {
        assertThat(generator.processNext()).isFalse();
        verify(llm, never()).generate(any());
    }

    @Test
    void aJobAsksTheLlmAndStoresTheCheckedAnswer() {
        ClaimedJob job = claim(1);

        assertThat(generator.processNext()).isTrue();

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).generate(request.capture());
        assertThat(request.getValue().prompt()).isEqualTo("Analyse AAPL (Apple Inc.), a STOCK on NASDAQ.");
        assertThat(request.getValue().schema()).isEqualTo("{\"title\":\"Analysis\"}");
        assertThat(request.getValue().webSearch()).isTrue();

        ArgumentCaptor<GeneratedAnalysis> analysis = ArgumentCaptor.forClass(GeneratedAnalysis.class);
        ArgumentCaptor<GenerationStats> stats = ArgumentCaptor.forClass(GenerationStats.class);
        verify(analyses).complete(eq(job.id()), eq(APPLE.id()), analysis.capture(), stats.capture(), eq("java"), eq(6 * 3600));
        assertThat(analysis.getValue().rating()).isEqualTo("BUY");
        assertThat(stats.getValue().model()).isEqualTo("test-model");
        assertThat(stats.getValue().inputTokens()).isEqualTo(120);
        assertThat(stats.getValue().outputTokens()).isEqualTo(340);
        verify(jobs, never()).release(any(), any(), anyBoolean());
        verify(jobs, never()).fail(any(), any());
    }

    @Test
    void aFailedFirstAttemptGoesBackInTheQueue() {
        ClaimedJob job = claim(1);
        when(llm.generate(any())).thenThrow(new LlmException("The vendor is down."));

        generator.processNext();

        verify(jobs).release(job.id(), "The vendor is down.", false);
        verify(jobs, never()).fail(any(), any());
        verify(analyses, never()).complete(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void aFailedRetryMarksTheJobFailed() {
        ClaimedJob job = claim(2);
        when(llm.generate(any())).thenThrow(new LlmException("The vendor is down."));

        generator.processNext();

        verify(jobs).fail(job.id(), "The vendor is down.");
        verify(jobs, never()).release(any(), any(), anyBoolean());
    }

    @Test
    void anAnswerWithoutSourcesIsNotStored() {
        ClaimedJob job = claim(2);
        when(llm.generate(any())).thenReturn(SampleAnalysis.llmResult(SampleAnalysis.JSON.replace(
                "[{\"title\": \"Report\", \"url\": \"https://example.com/report\"}]", "[]")));

        generator.processNext();

        verify(jobs).fail(job.id(), "The LLM answer is not valid: it cites no sources.");
        verify(analyses, never()).complete(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void aJobAbandonedOnItsLastAttemptIsFailedWithoutAskingTheLlm() {
        ClaimedJob job = claim(3);

        generator.processNext();

        verify(jobs).fail(job.id(), "The job did not finish in time.");
        verify(llm, never()).generate(any());
    }

    @Test
    void onShutdownTheJobIsHandedBackWithoutUsingUpAnAttempt() {
        ClaimedJob job = claim(1);
        when(llm.generate(any())).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new LlmException("The mock LLM was interrupted.");
        });

        generator.processNext();

        verify(jobs).release(job.id(), "The API shut down before the job finished.", true);
        assertThat(Thread.interrupted()).isTrue(); // still flagged, so the worker loop stops; cleared for the next test
    }

    @Test
    void aResultForAJobAnotherWorkerTookOverIsDropped() {
        claim(1);
        when(analyses.complete(any(), any(), any(), any(), anyString(), anyInt())).thenReturn(false);

        assertThat(generator.processNext()).isTrue();

        verify(jobs, never()).fail(any(), any());
    }

    @Test
    void longErrorsAreShortenedBeforeTheyAreStored() {
        ClaimedJob job = claim(2);
        when(llm.generate(any())).thenThrow(new LlmException("x".repeat(2000)));

        generator.processNext();

        verify(jobs).fail(job.id(), "x".repeat(500));
    }
}
