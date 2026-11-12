package com.onfinapp.api.analysis;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.onfinapp.api.instrument.Instrument;

/** The generation_jobs work queue, shared by both APIs. */
@Repository
public class GenerationJobRepository {

    public static final String ON_DEMAND = "ON_DEMAND";
    public static final String USER = "USER";

    /**
     * @param status PENDING, RUNNING, DONE or FAILED
     * @param finishedRecently finished within the cool-down that follows a failure
     */
    public record JobState(String status, boolean finishedRecently) {

        public boolean active() {
            return status.equals("PENDING") || status.equals("RUNNING");
        }

        public boolean failedRecently() {
            return status.equals("FAILED") && finishedRecently;
        }
    }

    /** A job this API has claimed. attempts counts this claim too. */
    public record ClaimedJob(UUID id, Instrument instrument, String trigger, int attempts, String correlationId) {
    }

    public enum RefreshOutcome {
        QUEUED,
        ALREADY_QUEUED,
        LIMIT_REACHED,
    }

    private static final String FIND_LATEST = """
            select status, coalesce(finished_at > now() - make_interval(secs => ?::int), false)
            from generation_jobs
            where kind = 'ANALYSIS' and instrument_id = ?
            order by created_at desc
            limit 1
            """;

    // The partial unique index allows one pending or running job per instrument.
    private static final String ENQUEUE = """
            insert into generation_jobs (kind, instrument_id, trigger, requested_by, correlation_id)
            values ('ANALYSIS', ?, ?, ?, ?)
            on conflict (kind, instrument_id) where status in ('PENDING', 'RUNNING') do nothing
            """;

    private static final String LOCK_USER = "select 1 from users where id = ? for update";

    private static final String COUNT_USER_REFRESHES = """
            select count(*) from generation_jobs
            where trigger = 'USER' and requested_by = ? and created_at > now() - interval '24 hours'
            """;

    // SKIP LOCKED: when both APIs ask at once, each gets a different job or none.
    private static final String CLAIM = """
            with claimed as (
                update generation_jobs
                set status = 'RUNNING', claimed_by = ?, claimed_at = now(), attempts = attempts + 1
                where id = (
                    select id from generation_jobs
                    where kind = 'ANALYSIS'
                      and (status = 'PENDING'
                           or (status = 'RUNNING' and claimed_at < now() - make_interval(secs => ?::int)))
                    order by created_at
                    limit 1
                    for update skip locked
                )
                returning id, instrument_id, trigger, attempts, correlation_id
            )
            select c.id, c.trigger, c.attempts, c.correlation_id, i.id, i.symbol, i.name, i.type, i.exchange
            from claimed c join instruments i on i.id = c.instrument_id
            """;

    private static final String RELEASE = """
            update generation_jobs
            set status = 'PENDING', claimed_by = null, claimed_at = null, error = ?, attempts = attempts - ?
            where id = ? and status = 'RUNNING'
            """;

    private static final String FAIL = """
            update generation_jobs set status = 'FAILED', error = ?, finished_at = now()
            where id = ? and status = 'RUNNING'
            """;

    private final JdbcTemplate jdbc;

    public GenerationJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest analysis job of an instrument; empty when there has never been one. */
    public Optional<JobState> findLatest(UUID instrumentId, int cooldownSeconds) {
        return jdbc.query(FIND_LATEST, (rs, i) -> new JobState(rs.getString(1), rs.getBoolean(2)),
                cooldownSeconds, instrumentId).stream().findFirst();
    }

    /**
     * Queues a job unless one is already pending or running for the instrument.
     *
     * @return true when a job was queued
     */
    public boolean enqueue(UUID instrumentId, String trigger, UUID requestedBy, String correlationId) {
        return jdbc.update(ENQUEUE, instrumentId, trigger, requestedBy, correlationId) == 1;
    }

    /** Queues a refresh for a user who has queued fewer than {@code limit} in the last 24 hours. */
    @Transactional
    public RefreshOutcome enqueueUserRefresh(UUID userId, UUID instrumentId, int limit, String correlationId) {
        // Locking the user's row makes concurrent refreshes by the same user wait their turn,
        // so two requests cannot both pass the limit check.
        jdbc.queryForList(LOCK_USER, userId);
        Long used = jdbc.queryForObject(COUNT_USER_REFRESHES, Long.class, userId);
        if (used != null && used >= limit) {
            return RefreshOutcome.LIMIT_REACHED;
        }
        return enqueue(instrumentId, USER, userId, correlationId) ? RefreshOutcome.QUEUED : RefreshOutcome.ALREADY_QUEUED;
    }

    /**
     * Takes the oldest job that is pending, or that has been running for longer than the timeout.
     * Empty when there is nothing to do.
     */
    public Optional<ClaimedJob> claim(String claimedBy, int timeoutSeconds) {
        return jdbc.query(CLAIM,
                (rs, i) -> new ClaimedJob(
                        rs.getObject(1, UUID.class),
                        new Instrument(rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8),
                                rs.getString(9)),
                        rs.getString(2), rs.getInt(3), rs.getString(4)),
                claimedBy, timeoutSeconds).stream().findFirst();
    }

    /**
     * Puts a claimed job back in the queue.
     *
     * @param refundAttempt the claim does not count as an attempt
     */
    public void release(UUID jobId, String error, boolean refundAttempt) {
        jdbc.update(RELEASE, error, refundAttempt ? 1 : 0, jobId);
    }

    public void fail(UUID jobId, String error) {
        jdbc.update(FAIL, error, jobId);
    }
}
