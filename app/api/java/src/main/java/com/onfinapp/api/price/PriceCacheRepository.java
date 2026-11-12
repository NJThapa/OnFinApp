package com.onfinapp.api.price;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The shared price_cache table. Both APIs read and write it, so they reuse each other's fetches. */
@Repository
public class PriceCacheRepository {

    public static final String QUOTE_KEY = "QUOTE";

    /** A stored provider response and whether it is still fresh. */
    public record CachedPayload(String json, boolean fresh) {
    }

    private static final String FRESH = """
            case when ?::int is not null then fetched_at > now() - make_interval(secs => ?::int)
                 else (fetched_at at time zone 'America/New_York')::date = (now() at time zone 'America/New_York')::date
            end""";
    private static final String READ =
            "select payload::text, " + FRESH + " from price_cache where instrument_id = ? and range = ?";
    private static final String READ_QUOTES = "select instrument_id, payload::text, " + FRESH
            + " from price_cache where range = 'QUOTE' and instrument_id = any(?)";
    private static final String WRITE = """
            insert into price_cache (instrument_id, range, payload, fetched_at)
            values (?, ?, ?::jsonb, now())
            on conflict (instrument_id, range)
            do update set payload = excluded.payload, fetched_at = excluded.fetched_at
            """;

    private final JdbcTemplate jdbc;

    public PriceCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Entry for one instrument and key. Fresh means younger than {@code ttlSeconds}, or, when that is null,
     * fetched on the current New York calendar day.
     */
    public Optional<CachedPayload> read(UUID instrumentId, String key, Integer ttlSeconds) {
        return jdbc.query(READ, (rs, i) -> new CachedPayload(rs.getString(1), rs.getBoolean(2)),
                ttlSeconds, ttlSeconds, instrumentId, key).stream().findFirst();
    }

    public Map<UUID, CachedPayload> readQuotes(Collection<UUID> instrumentIds, int ttlSeconds) {
        Map<UUID, CachedPayload> cached = new HashMap<>();
        if (instrumentIds.isEmpty()) {
            return cached;
        }
        jdbc.query(READ_QUOTES,
                rs -> {
                    cached.put(rs.getObject(1, UUID.class), new CachedPayload(rs.getString(2), rs.getBoolean(3)));
                },
                ttlSeconds, ttlSeconds, instrumentIds.toArray(UUID[]::new));
        return cached;
    }

    public void write(UUID instrumentId, String key, String json) {
        jdbc.update(WRITE, instrumentId, key, json);
    }
}
