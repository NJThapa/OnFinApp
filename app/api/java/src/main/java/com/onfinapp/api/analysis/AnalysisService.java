package com.onfinapp.api.analysis;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.onfinapp.api.analysis.AnalysisModels.AnalysisStatusResponse;
import com.onfinapp.api.analysis.AnalysisModels.RatingEntryResponse;
import com.onfinapp.api.analysis.AnalysisModels.RatingPageResponse;
import com.onfinapp.api.analysis.AnalysisModels.RatingRow;
import com.onfinapp.api.analysis.AnalysisModels.StoredAnalysis;
import com.onfinapp.api.analysis.GenerationJobRepository.JobState;
import com.onfinapp.api.analysis.GenerationJobRepository.RefreshOutcome;
import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.instrument.Instrument;
import com.onfinapp.api.instrument.InstrumentRepository;

/**
 * Reads analyses and ratings and decides when a new generation is queued. Generation itself runs in
 * {@link AnalysisGenerator}, never inside a request.
 */
@Service
@EnableConfigurationProperties(AnalysisProperties.class)
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final InstrumentRepository instruments;
    private final AnalysisRepository analyses;
    private final GenerationJobRepository jobs;
    private final WorkerSignal worker;
    private final AnalysisProperties properties;

    public AnalysisService(InstrumentRepository instruments, AnalysisRepository analyses, GenerationJobRepository jobs,
            WorkerSignal worker, AnalysisProperties properties) {
        this.instruments = instruments;
        this.analyses = analyses;
        this.jobs = jobs;
        this.worker = worker;
        this.properties = properties;
    }

    /** The stored analysis; queues a generation when there is none or it has expired. */
    public AnalysisStatusResponse get(String symbol, String correlationId) {
        Instrument instrument = require(symbol);
        StoredAnalysis latest = findLatest(instrument);
        Optional<JobState> job = findLatestJob(instrument);

        if (job.filter(JobState::active).isPresent()) {
            return AnalysisStatusResponse.of(instrument, AnalysisModels.GENERATING, latest);
        }
        if (job.filter(JobState::failedRecently).isPresent()) {
            return AnalysisStatusResponse.of(instrument, AnalysisModels.FAILED, latest);
        }
        if (latest != null && latest.fresh()) {
            return AnalysisStatusResponse.of(instrument, AnalysisModels.READY, latest);
        }

        if (jobs.enqueue(instrument.id(), GenerationJobRepository.ON_DEMAND, null, correlationId)) {
            log.info("Queued an analysis of {}: {}", instrument.symbol(),
                    latest == null ? "none exists" : "the last one expired");
            worker.signal();
        }
        return AnalysisStatusResponse.of(instrument, AnalysisModels.GENERATING, latest);
    }

    /**
     * A user's request for a new analysis.
     *
     * @throws ApiProblemException.RefreshLimit at the user's limit
     */
    public AnalysisStatusResponse refresh(String symbol, UUID userId, String correlationId) {
        Instrument instrument = require(symbol);
        StoredAnalysis latest = findLatest(instrument);
        Optional<JobState> job = findLatestJob(instrument);

        if (job.filter(JobState::active).isPresent()) {
            return AnalysisStatusResponse.of(instrument, AnalysisModels.GENERATING, latest);
        }
        if (latest != null && latest.young()) {
            log.info("Refresh of {} by user {} ignored: the analysis is younger than {} minutes",
                    instrument.symbol(), userId, properties.refreshMinAgeMinutes());
            return AnalysisStatusResponse.of(instrument, AnalysisModels.READY, latest);
        }

        RefreshOutcome outcome =
                jobs.enqueueUserRefresh(userId, instrument.id(), properties.refreshLimit(), correlationId);
        if (outcome == RefreshOutcome.LIMIT_REACHED) {
            log.info("User {} is at the refresh limit of {}; {} not refreshed",
                    userId, properties.refreshLimit(), instrument.symbol());
            throw new ApiProblemException.RefreshLimit(properties.refreshLimit());
        }
        if (outcome == RefreshOutcome.QUEUED) {
            log.info("User {} queued a refresh of the {} analysis", userId, instrument.symbol());
            worker.signal();
        }
        return AnalysisStatusResponse.of(instrument, AnalysisModels.GENERATING, latest);
    }

    public RatingPageResponse ratings(String symbol, String cursor, int limit) {
        long before = cursor == null || cursor.isEmpty() ? RatingCursor.START : RatingCursor.decode(cursor);
        Instrument instrument = require(symbol);

        // One extra row tells whether there is a next page.
        List<RatingRow> rows = analyses.listRatings(instrument.id(), before, limit + 1);
        List<RatingRow> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        return new RatingPageResponse(
                page.stream()
                        .map(r -> new RatingEntryResponse(r.rating(), r.confidence(), r.rationale(), r.createdAt()))
                        .toList(),
                rows.size() > limit ? RatingCursor.encode(page.get(page.size() - 1).seq()) : null);
    }

    private StoredAnalysis findLatest(Instrument instrument) {
        return analyses.findLatest(instrument.id(), properties.refreshMinAgeMinutes() * 60).orElse(null);
    }

    private Optional<JobState> findLatestJob(Instrument instrument) {
        return jobs.findLatest(instrument.id(), properties.retryAfterMinutes() * 60);
    }

    private Instrument require(String symbol) {
        String normalized = Instrument.normalize(symbol);
        return instruments.find(normalized).orElseThrow(() -> new ApiProblemException.InstrumentNotFound(normalized));
    }
}
