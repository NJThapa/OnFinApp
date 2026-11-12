using Npgsql;
using OnFinApp.Api.Prices;

namespace OnFinApp.Api.Instruments;

public interface IInstrumentRepository
{
    /// <paramref name="symbol"/> must already be normalised (upper case).
    Task<Instrument?> FindAsync(string symbol, CancellationToken cancel);

    /// Matches after the cursor position, best first. <paramref name="query"/> must be normalised; empty matches all.
    Task<IReadOnlyList<InstrumentMatch>> SearchAsync(string query, InstrumentCursor after, int limit, CancellationToken cancel);

    Task UpsertAsync(IReadOnlyList<ProviderInstrument> instruments, CancellationToken cancel);
}

public sealed class InstrumentRepository(NpgsqlDataSource db) : IInstrumentRepository
{
    private const string Find = "select id, symbol, name, type, exchange from instruments where symbol = $1";

    private const string Search = """
        select id, symbol, name, type, exchange, rank from (
            select id, symbol, name, type, exchange,
                   case when symbol = $1 then 0 when symbol like $2 escape '\' then 1 else 2 end as rank
            from instruments
            where $1 = '' or symbol like $2 escape '\' or upper(name) like $3 escape '\'
        ) m
        where (rank, symbol collate "C") > ($4, $5)
        order by rank, symbol collate "C"
        limit $6
        """;

    private const string Upsert = """
        insert into instruments (symbol, name, type, exchange)
        values ($1, $2, $3, $4)
        on conflict (symbol) do update
        set name = excluded.name, type = excluded.type, exchange = excluded.exchange, updated_at = now()
        where (instruments.name, instruments.type, instruments.exchange)
              is distinct from (excluded.name, excluded.type, excluded.exchange)
        """;

    public async Task<Instrument?> FindAsync(string symbol, CancellationToken cancel)
    {
        await using var cmd = db.CreateCommand(Find);
        cmd.Parameters.Add(new() { Value = symbol });
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        return await reader.ReadAsync(cancel) ? Read(reader) : null;
    }

    public async Task<IReadOnlyList<InstrumentMatch>> SearchAsync(
        string query, InstrumentCursor after, int limit, CancellationToken cancel)
    {
        // % and _ in the query are plain characters, not wildcards.
        var escaped = query.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_");
        await using var cmd = db.CreateCommand(Search);
        cmd.Parameters.Add(new() { Value = query });
        cmd.Parameters.Add(new() { Value = escaped + "%" });
        cmd.Parameters.Add(new() { Value = "%" + escaped + "%" });
        cmd.Parameters.Add(new() { Value = after.Rank });
        cmd.Parameters.Add(new() { Value = after.Symbol });
        cmd.Parameters.Add(new() { Value = limit });

        var matches = new List<InstrumentMatch>();
        await using var reader = await cmd.ExecuteReaderAsync(cancel);
        while (await reader.ReadAsync(cancel)) matches.Add(new InstrumentMatch(Read(reader), reader.GetInt32(5)));
        return matches;
    }

    public async Task UpsertAsync(IReadOnlyList<ProviderInstrument> instruments, CancellationToken cancel)
    {
        if (instruments.Count == 0) return;
        await using var batch = db.CreateBatch();
        foreach (var instrument in instruments)
        {
            var cmd = new NpgsqlBatchCommand(Upsert);
            cmd.Parameters.Add(new() { Value = InstrumentService.Normalize(instrument.Symbol) });
            cmd.Parameters.Add(new() { Value = instrument.Name });
            cmd.Parameters.Add(new() { Value = instrument.Type });
            cmd.Parameters.Add(new() { Value = instrument.Exchange });
            batch.BatchCommands.Add(cmd);
        }
        await batch.ExecuteNonQueryAsync(cancel);
    }

    internal static Instrument Read(NpgsqlDataReader reader) =>
        new(reader.GetGuid(0), reader.GetString(1), reader.GetString(2), reader.GetString(3), reader.GetString(4));
}
