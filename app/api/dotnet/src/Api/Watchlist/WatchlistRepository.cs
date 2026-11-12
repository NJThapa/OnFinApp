using Npgsql;
using OnFinApp.Api.Instruments;

namespace OnFinApp.Api.Watchlist;

public enum FollowOutcome
{
    Followed,
    AlreadyFollowing,
    LimitReached,
}

public interface IWatchlistRepository
{
    /// Followed instruments in the order followed.
    Task<IReadOnlyList<Instrument>> ListAsync(Guid userId, CancellationToken cancel);

    Task<bool> IsFollowingAsync(Guid userId, Guid instrumentId, CancellationToken cancel);

    /// Adds the instrument unless the user already follows <paramref name="limit"/> instruments.
    Task<FollowOutcome> FollowAsync(Guid userId, Guid instrumentId, int limit, CancellationToken cancel);

    Task UnfollowAsync(Guid userId, Guid instrumentId, CancellationToken cancel);
}

public sealed class WatchlistRepository(NpgsqlDataSource db) : IWatchlistRepository
{
    private const string List = """
        select i.id, i.symbol, i.name, i.type, i.exchange
        from watchlist_items w join instruments i on i.id = w.instrument_id
        where w.user_id = $1
        order by w.created_at, i.symbol
        """;
    private const string IsFollowing = "select exists (select 1 from watchlist_items where user_id = $1 and instrument_id = $2)";
    private const string LockUser = "select 1 from users where id = $1 for update";
    private const string Count = "select count(*) from watchlist_items where user_id = $1";
    private const string Insert = "insert into watchlist_items (user_id, instrument_id) values ($1, $2)";
    private const string Delete = "delete from watchlist_items where user_id = $1 and instrument_id = $2";

    public async Task<IReadOnlyList<Instrument>> ListAsync(Guid userId, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(List);
        cmd.Parameters.Add(new() { Value = userId });
        var followed = new List<Instrument>();
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        while (await reader.ReadAsync(cancel)) followed.Add(InstrumentRepository.Read(reader));
        return followed;
    }

    public async Task<bool> IsFollowingAsync(Guid userId, Guid instrumentId, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(IsFollowing);
        cmd.Parameters.Add(new() { Value = userId });
        cmd.Parameters.Add(new() { Value = instrumentId });
        return (bool)(await cmd.ExecuteScalarAsync(cancel))!;
    }

    public async Task<FollowOutcome> FollowAsync(Guid userId, Guid instrumentId, int limit, CancellationToken cancel)
    {
        await using var connection = await db.OpenConnectionAsync(cancel);
        await using var transaction = await connection.BeginTransactionAsync(cancel);

        // Locking the user's row makes concurrent follows by the same user wait their turn,
        // so two requests cannot both pass the limit check.
        await ScalarAsync(connection, transaction, LockUser, cancel, userId);

        if ((bool)(await ScalarAsync(connection, transaction, IsFollowing, cancel, userId, instrumentId))!)
        {
            return FollowOutcome.AlreadyFollowing;
        }
        if ((long)(await ScalarAsync(connection, transaction, Count, cancel, userId))! >= limit)
        {
            return FollowOutcome.LimitReached;
        }
        await ScalarAsync(connection, transaction, Insert, cancel, userId, instrumentId);
        await transaction.CommitAsync(cancel);
        return FollowOutcome.Followed;
    }

    public async Task UnfollowAsync(Guid userId, Guid instrumentId, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(Delete);
        cmd.Parameters.Add(new() { Value = userId });
        cmd.Parameters.Add(new() { Value = instrumentId });
        await cmd.ExecuteNonQueryAsync(cancel);
    }

    private static async Task<object?> ScalarAsync(
        NpgsqlConnection connection, NpgsqlTransaction transaction, string sql, CancellationToken cancel, params Guid[] ids)
    {
        await using var cmd = new NpgsqlCommand(sql, connection, transaction);
        foreach (var id in ids) cmd.Parameters.Add(new() { Value = id });
        return await cmd.ExecuteScalarAsync(cancel);
    }
}
