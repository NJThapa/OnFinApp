package com.example.api.user;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    public record User(UUID id, String email, String name) {
    }

    private static final String UPSERT = """
            insert into users (issuer, subject, email, name)
            values (?, ?, ?, ?)
            on conflict (issuer, subject)
            do update set email = excluded.email, name = excluded.name, updated_at = now()
            returning id, email, name
            """;

    private final JdbcTemplate jdbc;

    public UserController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Maps the authenticated token to the local user row, creating it on first sight. */
    @GetMapping("/api/v1/me")
    public User me(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.queryForObject(
                UPSERT,
                (rs, row) -> new User(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name")),
                jwt.getClaimAsString("iss"),
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"));
    }
}
