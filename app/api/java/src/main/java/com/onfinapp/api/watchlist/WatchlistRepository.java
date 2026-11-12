package com.onfinapp.api.watchlist;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.onfinapp.api.instrument.Instrument;
import com.onfinapp.api.instrument.InstrumentRepository;

@Repository
public class WatchlistRepository {

    private static final String LIST = """
            select i.id, i.symbol, i.name, i.type, i.exchange
            from watchlist_items w join instruments i on i.id = w.instrument_id
            where w.user_id = ?
            order by w.created_at, i.symbol
            """;
    private static final String IS_FOLLOWING =
            "select exists (select 1 from watchlist_items where user_id = ? and instrument_id = ?)";
    private static final String LOCK_USER = "select 1 from users where id = ? for update";
    private static final String COUNT = "select count(*) from watchlist_items where user_id = ?";
    private static final String INSERT = "insert into watchlist_items (user_id, instrument_id) values (?, ?)";
    private static final String DELETE = "delete from watchlist_items where user_id = ? and instrument_id = ?";

    private final JdbcTemplate jdbc;

    public WatchlistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Followed instruments in the order followed. */
    public List<Instrument> list(UUID userId) {
        return jdbc.query(LIST, InstrumentRepository.ROW, userId);
    }

    public boolean isFollowing(UUID userId, UUID instrumentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(IS_FOLLOWING, Boolean.class, userId, instrumentId));
    }

    /**
     * Locks the user's row until the surrounding transaction ends, so concurrent follows by the same user
     * wait their turn and cannot both pass the limit check.
     */
    public void lockUser(UUID userId) {
        jdbc.queryForList(LOCK_USER, userId);
    }

    public int count(UUID userId) {
        Integer count = jdbc.queryForObject(COUNT, Integer.class, userId);
        return count == null ? 0 : count;
    }

    public void insert(UUID userId, UUID instrumentId) {
        jdbc.update(INSERT, userId, instrumentId);
    }

    public void delete(UUID userId, UUID instrumentId) {
        jdbc.update(DELETE, userId, instrumentId);
    }
}
