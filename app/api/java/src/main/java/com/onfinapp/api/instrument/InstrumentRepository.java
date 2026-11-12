package com.onfinapp.api.instrument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.onfinapp.api.price.ProviderInstrument;

@Repository
public class InstrumentRepository {

    private static final String FIND = "select id, symbol, name, type, exchange from instruments where symbol = ?";

    private static final String SEARCH = """
            select id, symbol, name, type, exchange, rank from (
                select id, symbol, name, type, exchange,
                       case when symbol = ? then 0 when symbol like ? escape '\\' then 1 else 2 end as rank
                from instruments
                where ? = '' or symbol like ? escape '\\' or upper(name) like ? escape '\\'
            ) m
            where (rank, symbol collate "C") > (?, ?)
            order by rank, symbol collate "C"
            limit ?
            """;

    private static final String UPSERT = """
            insert into instruments (symbol, name, type, exchange)
            values (?, ?, ?, ?)
            on conflict (symbol) do update
            set name = excluded.name, type = excluded.type, exchange = excluded.exchange, updated_at = now()
            where (instruments.name, instruments.type, instruments.exchange)
                  is distinct from (excluded.name, excluded.type, excluded.exchange)
            """;

    public static final RowMapper<Instrument> ROW = (rs, i) -> new Instrument(
            rs.getObject("id", UUID.class), rs.getString("symbol"), rs.getString("name"),
            rs.getString("type"), rs.getString("exchange"));

    private final JdbcTemplate jdbc;

    public InstrumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param symbol already normalised (upper case) */
    public Optional<Instrument> find(String symbol) {
        return jdbc.query(FIND, ROW, symbol).stream().findFirst();
    }

    /** Matches after the cursor position, best first. The query must be normalised; empty matches all. */
    public List<InstrumentMatch> search(String query, InstrumentCursor after, int limit) {
        // % and _ in the query are plain characters, not wildcards.
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.query(SEARCH, (rs, i) -> new InstrumentMatch(ROW.mapRow(rs, i), rs.getInt("rank")),
                query, escaped + "%", query, escaped + "%", "%" + escaped + "%", after.rank(), after.symbol(), limit);
    }

    public void upsert(List<ProviderInstrument> instruments) {
        jdbc.batchUpdate(UPSERT, instruments, 500, (ps, instrument) -> {
            ps.setString(1, Instrument.normalize(instrument.symbol()));
            ps.setString(2, instrument.name());
            ps.setString(3, instrument.type());
            ps.setString(4, instrument.exchange());
        });
    }
}
