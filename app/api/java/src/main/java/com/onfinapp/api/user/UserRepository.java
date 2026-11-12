package com.onfinapp.api.user;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String UPSERT = """
            insert into users (issuer, subject, email, name)
            values (?, ?, ?, ?)
            on conflict (issuer, subject)
            do update set email = excluded.email, name = excluded.name, updated_at = now()
            returning id, email, name, (xmax = 0) as created
            """;

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param created true when this call created the row */
    public record UpsertedUser(User user, boolean created) {
    }

    /** Creates or updates the row keyed by (issuer, subject) and returns it. */
    public UpsertedUser upsert(String issuer, String subject, String email, String name) {
        return jdbc.queryForObject(
                UPSERT,
                (rs, row) -> new UpsertedUser(
                        new User(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name")),
                        rs.getBoolean("created")),
                issuer, subject, email, name);
    }
}
