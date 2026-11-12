package com.onfinapp.api.analysis;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onfinapp.api.analysis.AnalysisModels.AnalysisContent;
import com.onfinapp.api.analysis.AnalysisModels.GeneratedAnalysis;
import com.onfinapp.api.analysis.AnalysisModels.GenerationStats;
import com.onfinapp.api.analysis.AnalysisModels.RatingRow;
import com.onfinapp.api.analysis.AnalysisModels.StoredAnalysis;
import com.onfinapp.api.llm.Source;

@Repository
public class AnalysisRepository {

    private static final String FIND_LATEST = """
            select a.content::text, a.sources::text, r.rating, r.confidence, r.rationale,
                   a.generated_at, a.expires_at, a.generated_by,
                   a.expires_at > now(), a.generated_at > now() - make_interval(secs => ?::int)
            from ratings r join analyses a on a.id = r.analysis_id
            where r.instrument_id = ?
            order by r.seq desc
            limit 1
            """;

    private static final String LATEST_RATINGS = """
            select distinct on (instrument_id) instrument_id, rating
            from ratings
            where instrument_id = any(?)
            order by instrument_id, seq desc
            """;

    private static final String LIST_RATINGS = """
            select seq, rating, confidence, rationale, created_at
            from ratings
            where instrument_id = ? and seq < ?
            order by seq desc
            limit ?
            """;

    private static final String MARK_JOB_DONE = """
            update generation_jobs set status = 'DONE', error = null, finished_at = now()
            where id = ? and status = 'RUNNING'
            """;

    // Times are cut to whole seconds so both APIs write them the same way in responses.
    private static final String INSERT = """
            with a as (
                insert into analyses (instrument_id, content, sources, generated_at, expires_at,
                                      generated_by, model, duration_ms, input_tokens, output_tokens)
                values (?, ?::jsonb, ?::jsonb, date_trunc('second', now()),
                        date_trunc('second', now()) + make_interval(secs => ?::int), ?, ?, ?, ?, ?)
                returning id, instrument_id, generated_at
            )
            insert into ratings (instrument_id, analysis_id, rating, confidence, rationale, created_at)
            select instrument_id, id, ?, ?, ?, generated_at from a
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AnalysisRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The newest analysis of an instrument with its rating. */
    public Optional<StoredAnalysis> findLatest(UUID instrumentId, int minAgeSeconds) {
        return jdbc.query(FIND_LATEST,
                (rs, i) -> new StoredAnalysis(
                        parse(rs.getString(1), new TypeReference<AnalysisContent>() {
                        }),
                        parse(rs.getString(2), new TypeReference<List<Source>>() {
                        }),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), rs.getString(8),
                        rs.getBoolean(9), rs.getBoolean(10)),
                minAgeSeconds, instrumentId).stream().findFirst();
    }

    /** The current rating of each instrument that has one. */
    public Map<UUID, String> latestRatings(Collection<UUID> instrumentIds) {
        Map<UUID, String> ratings = new HashMap<>();
        if (instrumentIds.isEmpty()) {
            return ratings;
        }
        jdbc.query(LATEST_RATINGS,
                rs -> {
                    ratings.put(rs.getObject(1, UUID.class), rs.getString(2));
                },
                (Object) instrumentIds.toArray(UUID[]::new));
        return ratings;
    }

    /** Ratings older than {@code beforeSeq}, newest first. */
    public List<RatingRow> listRatings(UUID instrumentId, long beforeSeq, int limit) {
        return jdbc.query(LIST_RATINGS,
                (rs, i) -> new RatingRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()),
                instrumentId, beforeSeq, limit);
    }

    /**
     * Stores the analysis and its rating and marks the job done, in one transaction.
     *
     * @return false, with nothing stored, when the job is no longer running (another worker took it over)
     */
    @Transactional
    public boolean complete(UUID jobId, UUID instrumentId, GeneratedAnalysis analysis, GenerationStats stats,
            String generatedBy, int ttlSeconds) {
        if (jdbc.update(MARK_JOB_DONE, jobId) == 0) {
            return false;
        }
        jdbc.update(INSERT,
                instrumentId, write(analysis.content()), write(analysis.sources()), ttlSeconds,
                generatedBy, stats.model(), stats.durationMs(), stats.inputTokens(), stats.outputTokens(),
                analysis.rating(), analysis.confidence(), analysis.rationale());
        return true;
    }

    private <T> T parse(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored analysis cannot be read.", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An analysis cannot be written as JSON.", e);
        }
    }
}
