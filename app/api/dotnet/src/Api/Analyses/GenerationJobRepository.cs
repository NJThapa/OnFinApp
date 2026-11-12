using Npgsql;
using NpgsqlTypes;
using OnFinApp.Api.Instruments;

namespace OnFinApp.Api.Analyses;

public static class JobTriggers
{
    public const string OnDemand = "ON_DEMAND";
    public const string User = "USER";
}

/// <param name="Status">PENDING, RUNNING, DONE or FAILED.</param>
/// <param name="FinishedRecently">Finished within the cool-down that follows a failure.</param>
public sealed record JobState(string Status, bool FinishedRecently)
{
    public bool Active => Status is "PENDING" or "RUNNING";
    public bool FailedRecently => Status == "FAILED" && FinishedRecently;
}

/// A job this API has claimed. Attempts counts this claim too.
public sealed record ClaimedJob(Guid Id, Instrument Instrument, string Trigger, int Attempts, string? CorrelationId);

public enum RefreshOutcome
{
    Queued,
    AlreadyQueued,
    LimitReached,
}

/// The generation_jobs work queue, shared by both APIs.
public interface IGenerationJobRepository
{
    /// The newest analysis job of an instrument, or null when there has never been one.
    Task<JobState?> FindLatestAsync(Guid instrumentId, int cooldownSeconds, CancellationToken cancel);

    /// Queues a job unless one is already pending or running for the instrument. True when a job was queued.
    Task<bool> EnqueueAsync(Guid instrumentId, string trigger, string? correlationId, CancellationToken cancel);

    /// Queues a refresh for a user who has queued fewer than <paramref name="limit"/> in the last 24 hours.
    Task<RefreshOutcome> EnqueueUserRefreshAsync(
        Guid userId, Guid instrumentId, int limit, string? correlationId, CancellationToken cancel);

    /// Takes the oldest job that is pending, or that has been running for longer than the timeout.
    /// Null when there is nothing to do.
    Task<ClaimedJob?> ClaimAsync(string claimedBy, int timeoutSeconds, CancellationToken cancel);

    /// Puts a claimed job back in the queue. <paramref name="refundAttempt"/>: the claim does not count as an attempt.
    Task ReleaseAsync(Guid jobId, string error, bool refundAttempt, CancellationToken cancel);

    Task FailAsync(Guid jobId, string error, CancellationToken cancel);
}

public sealed class GenerationJobRepository(NpgsqlDataSource db) : IGenerationJobRepository
{
    private const string FindLatest = """
        select status, coalesce(finished_at > now() - make_interval(secs => $2::int), false)
        from generation_jobs
        where kind = 'ANALYSIS' and instrument_id = $1
        order by created_at desc
        limit 1
        """;

    // The partial unique index allows one pending or running job per instrument.
    private const string Enqueue = """
        insert into generation_jobs (kind, instrument_id, trigger, requested_by, correlation_id)
        values ('ANALYSIS', $1, $2, $3, $4)
        on conflict (kind, instrument_id) where status in ('PENDING', 'RUNNING') do nothing
        """;

    private const string LockUser = "select 1 from users where id = $1 for update";

    private const string CountUserRefreshes = """
        select count(*) from generation_jobs
        where trigger = 'USER' and requested_by = $1 and created_at > now() - interval '24 hours'
        """;

    // SKIP LOCKED: when both APIs ask at once, each gets a different job or none.
    private const string Claim = """
        with claimed as (
            update generation_jobs
            set status = 'RUNNING', claimed_by = $1, claimed_at = now(), attempts = attempts + 1
            where id = (
                select id from generation_jobs
                where kind = 'ANALYSIS'
                  and (status = 'PENDING'
                       or (status = 'RUNNING' and claimed_at < now() - make_interval(secs => $2::int)))
                order by created_at
                limit 1
                for update skip locked
            )
            returning id, instrument_id, trigger, attempts, correlation_id
        )
        select c.id, c.trigger, c.attempts, c.correlation_id, i.id, i.symbol, i.name, i.type, i.exchange
        from claimed c join instruments i on i.id = c.instrument_id
        """;

    private const string Release = """
        update generation_jobs
        set status = 'PENDING', claimed_by = null, claimed_at = null, error = $2, attempts = attempts - $3
        where id = $1 and status = 'RUNNING'
        """;

    private const string Fail = """
        update generation_jobs set status = 'FAILED', error = $2, finished_at = now()
        where id = $1 and status = 'RUNNING'
        """;

    public async Task<JobState?> FindLatestAsync(Guid instrumentId, int cooldownSeconds, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(FindLatest);
        cmd.Parameters.Add(new() { Value = instrumentId });
        cmd.Parameters.Add(new() { Value = cooldownSeconds });
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        return await reader.ReadAsync(cancel) ? new JobState(reader.GetString(0), reader.GetBoolean(1)) : null;
    }

    public async Task<bool> EnqueueAsync(Guid instrumentId, string trigger, string? correlationId, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(Enqueue);
        AddEnqueueParameters(cmd, instrumentId, trigger, requestedBy: null, correlationId);
        return await cmd.ExecuteNonQueryAsync(cancel) == 1;
    }

    public async Task<RefreshOutcome> EnqueueUserRefreshAsync(
        Guid userId, Guid instrumentId, int limit, string? correlationId, CancellationToken cancel)
    {
        await using var connection = await db.OpenConnectionAsync(cancel);
        await using var transaction = await connection.BeginTransactionAsync(cancel);

        // Locking the user's row makes concurrent refreshes by the same user wait their turn,
        // so two requests cannot both pass the limit check.
        await using (var lockUser = new NpgsqlCommand(LockUser, connection, transaction))
        {
            lockUser.Parameters.Add(new() { Value = userId });
            await lockUser.ExecuteScalarAsync(cancel);
        }
        await using (var count = new NpgsqlCommand(CountUserRefreshes, connection, transaction))
        {
            count.Parameters.Add(new() { Value = userId });
            if ((long)(await count.ExecuteScalarAsync(cancel))! >= limit) return RefreshOutcome.LimitReached;
        }
        await using (var enqueue = new NpgsqlCommand(Enqueue, connection, transaction))
        {
            AddEnqueueParameters(enqueue, instrumentId, JobTriggers.User, userId, correlationId);
            if (await enqueue.ExecuteNonQueryAsync(cancel) == 0) return RefreshOutcome.AlreadyQueued;
        }
        await transaction.CommitAsync(cancel);
        return RefreshOutcome.Queued;
    }

    public async Task<ClaimedJob?> ClaimAsync(string claimedBy, int timeoutSeconds, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(Claim);
        cmd.Parameters.Add(new() { Value = claimedBy });
        cmd.Parameters.Add(new() { Value = timeoutSeconds });
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        if (!await reader.ReadAsync(cancel)) return null;
        var instrument = new Instrument(
            reader.GetGuid(4), reader.GetString(5), reader.GetString(6), reader.GetString(7), reader.GetString(8));
        return new ClaimedJob(
            reader.GetGuid(0), instrument, reader.GetString(1), reader.GetInt32(2), reader.IsDBNull(3) ? null : reader.GetString(3));
    }

    public async Task ReleaseAsync(Guid jobId, string error, bool refundAttempt, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(Release);
        cmd.Parameters.Add(new() { Value = jobId });
        cmd.Parameters.Add(new() { Value = error });
        cmd.Parameters.Add(new() { Value = refundAttempt ? 1 : 0 });
        await cmd.ExecuteNonQueryAsync(cancel);
    }

    public async Task FailAsync(Guid jobId, string error, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(Fail);
        cmd.Parameters.Add(new() { Value = jobId });
        cmd.Parameters.Add(new() { Value = error });
        await cmd.ExecuteNonQueryAsync(cancel);
    }

    private static void AddEnqueueParameters(
        NpgsqlCommand cmd, Guid instrumentId, string trigger, Guid? requestedBy, string? correlationId)
    {
        cmd.Parameters.Add(new() { Value = instrumentId });
        cmd.Parameters.Add(new() { Value = trigger });
        cmd.Parameters.Add(new() { Value = (object?)requestedBy ?? DBNull.Value, NpgsqlDbType = NpgsqlDbType.Uuid });
        cmd.Parameters.Add(new() { Value = (object?)correlationId ?? DBNull.Value, NpgsqlDbType = NpgsqlDbType.Text });
    }
}
