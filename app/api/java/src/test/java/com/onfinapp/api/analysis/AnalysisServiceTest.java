package com.onfinapp.api.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.onfinapp.api.analysis.AnalysisModels.AnalysisStatusResponse;
import com.onfinapp.api.analysis.AnalysisModels.RatingEntryResponse;
import com.onfinapp.api.analysis.AnalysisModels.RatingPageResponse;
import com.onfinapp.api.analysis.AnalysisModels.RatingRow;
import com.onfinapp.api.analysis.GenerationJobRepository.JobState;
import com.onfinapp.api.analysis.GenerationJobRepository.RefreshOutcome;
import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.instrument.Instrument;
import com.onfinapp.api.instrument.InstrumentRepository;

class AnalysisServiceTest {

    private static final Instrument APPLE = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "STOCK", "NASDAQ");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final int MIN_AGE_SECONDS = 3600;
    private static final int COOLDOWN_SECONDS = 300;

    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final AnalysisRepository analyses = mock(AnalysisRepository.class);
    private final GenerationJobRepository jobs = mock(GenerationJobRepository.class);
    private final WorkerSignal worker = mock(WorkerSignal.class);
    private final AnalysisService service = new AnalysisService(instruments, analyses, jobs, worker,
            new AnalysisProperties(24, 3, 60, 5, true, 2000, 300));

    @BeforeEach
    void defaults() {
        when(instruments.find(anyString())).thenReturn(Optional.empty());
        when(instruments.find("AAPL")).thenReturn(Optional.of(APPLE));
        when(analyses.findLatest(APPLE.id(), MIN_AGE_SECONDS)).thenReturn(Optional.empty());
        when(jobs.findLatest(APPLE.id(), COOLDOWN_SECONDS)).thenReturn(Optional.empty());
        when(jobs.enqueue(any(), anyString(), any(), any())).thenReturn(true);
        when(jobs.enqueueUserRefresh(any(), any(), anyInt(), any())).thenReturn(RefreshOutcome.QUEUED);
    }

    private void latest(boolean fresh, boolean young) {
        when(analyses.findLatest(APPLE.id(), MIN_AGE_SECONDS))
                .thenReturn(Optional.of(SampleAnalysis.stored("BUY", fresh, young)));
    }

    private void latestJob(String status, boolean finishedRecently) {
        when(jobs.findLatest(APPLE.id(), COOLDOWN_SECONDS)).thenReturn(Optional.of(new JobState(status, finishedRecently)));
    }

    @Test
    void aValidAnalysisIsReturnedWithoutQueueingAnything() {
        latest(true, true);

        AnalysisStatusResponse result = service.get(" aapl ", "c-1");

        assertThat(result.symbol()).isEqualTo("AAPL");
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.analysis().rating()).isEqualTo("BUY");
        assertThat(result.analysis().generatedBy()).isEqualTo("dotnet");
        verify(jobs, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void withNoAnalysisAGenerationIsQueued() {
        AnalysisStatusResponse result = service.get("AAPL", "c-1");

        assertThat(result.status()).isEqualTo("GENERATING");
        assertThat(result.analysis()).isNull();
        verify(jobs).enqueue(APPLE.id(), "ON_DEMAND", null, "c-1");
        verify(worker).signal();
    }

    @Test
    void anExpiredAnalysisIsReturnedWhileItsReplacementIsGenerated() {
        latest(false, false);
        latestJob("DONE", false);

        AnalysisStatusResponse result = service.get("AAPL", null);

        assertThat(result.status()).isEqualTo("GENERATING");
        assertThat(result.analysis()).isNotNull();
        verify(jobs).enqueue(APPLE.id(), "ON_DEMAND", null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING"})
    void whileAJobIsQueuedOrRunningNothingMoreIsQueued(String status) {
        latest(true, false);
        latestJob(status, false);

        assertThat(service.get("AAPL", null).status()).isEqualTo("GENERATING");
        assertThat(service.refresh("AAPL", USER_ID, null).status()).isEqualTo("GENERATING");
        verify(jobs, never()).enqueue(any(), any(), any(), any());
        verify(jobs, never()).enqueueUserRefresh(any(), any(), anyInt(), any());
    }

    @Test
    void afterARecentFailureTheStatusIsFailedAndNothingIsQueued() {
        latest(false, false);
        latestJob("FAILED", true);

        AnalysisStatusResponse result = service.get("AAPL", null);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.analysis()).isNotNull(); // the last valid analysis stays visible
        verify(jobs, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void afterTheCoolDownAFailedGenerationIsTriedAgain() {
        latestJob("FAILED", false);

        assertThat(service.get("AAPL", null).status()).isEqualTo("GENERATING");
        verify(jobs).enqueue(APPLE.id(), "ON_DEMAND", null, null);
    }

    @Test
    void aRefreshIsIgnoredWhileTheAnalysisIsYoung() {
        latest(true, true);

        assertThat(service.refresh("AAPL", USER_ID, null).status()).isEqualTo("READY");
        verify(jobs, never()).enqueueUserRefresh(any(), any(), anyInt(), any());
    }

    @Test
    void aRefreshOfAnOlderAnalysisIsQueuedForTheUser() {
        latest(true, false);

        AnalysisStatusResponse result = service.refresh("AAPL", USER_ID, "c-2");

        assertThat(result.status()).isEqualTo("GENERATING");
        assertThat(result.analysis()).isNotNull();
        verify(jobs).enqueueUserRefresh(USER_ID, APPLE.id(), 3, "c-2");
        verify(worker).signal();
    }

    @Test
    void aRefreshAtTheUsersLimitIsRefused() {
        when(jobs.enqueueUserRefresh(any(), any(), anyInt(), any())).thenReturn(RefreshOutcome.LIMIT_REACHED);

        assertThatThrownBy(() -> service.refresh("AAPL", USER_ID, null))
                .isInstanceOf(ApiProblemException.RefreshLimit.class)
                .hasMessage("You can refresh up to 3 analyses in 24 hours.");
    }

    @Test
    void aRefreshThatLosesTheRaceToAnotherRequestJoinsItsGeneration() {
        when(jobs.enqueueUserRefresh(any(), any(), anyInt(), any())).thenReturn(RefreshOutcome.ALREADY_QUEUED);

        assertThat(service.refresh("AAPL", USER_ID, null).status()).isEqualTo("GENERATING");
        verify(worker, never()).signal();
    }

    @Test
    void ratingHistoryIsPagedNewestFirst() {
        Instant at = Instant.parse("2026-11-11T17:00:00Z");
        List<RatingRow> rows = LongStream.of(5, 4, 3)
                .mapToObj(seq -> new RatingRow(seq, "HOLD", "LOW", "Rationale " + seq, at)).toList();
        when(analyses.listRatings(APPLE.id(), RatingCursor.START, 3)).thenReturn(rows);
        when(analyses.listRatings(APPLE.id(), 4, 3)).thenReturn(rows.subList(2, 3));

        RatingPageResponse first = service.ratings("aapl", null, 2);
        RatingPageResponse last = service.ratings("aapl", first.nextCursor(), 2);

        assertThat(first.items()).containsExactly(
                new RatingEntryResponse("HOLD", "LOW", "Rationale 5", at),
                new RatingEntryResponse("HOLD", "LOW", "Rationale 4", at));
        assertThat(first.nextCursor()).isEqualTo(RatingCursor.encode(4));
        assertThat(last.items()).extracting(RatingEntryResponse::rationale).containsExactly("Rationale 3");
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void unknownTickersAndBadCursorsAreRefused() {
        assertThatThrownBy(() -> service.get("NOPE", null)).isInstanceOf(ApiProblemException.InstrumentNotFound.class);
        assertThatThrownBy(() -> service.refresh("NOPE", USER_ID, null))
                .isInstanceOf(ApiProblemException.InstrumentNotFound.class);
        assertThatThrownBy(() -> service.ratings("NOPE", null, 20)).isInstanceOf(ApiProblemException.InstrumentNotFound.class);
        assertThatThrownBy(() -> service.ratings("AAPL", "nonsense!", 20)).isInstanceOf(ApiProblemException.InvalidCursor.class);
        verify(analyses, never()).listRatings(any(), anyLong(), anyInt());
        verify(jobs, never()).enqueue(any(), eq("ON_DEMAND"), any(), any());
    }
}
