package com.onfinapp.api.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.onfinapp.api.user.UserRepository.UpsertedUser;

/** Maps the authenticated token to the local user row, creating it on first sight. */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public User resolve(Jwt jwt) {
        UpsertedUser upserted = users.upsert(
                jwt.getClaimAsString("iss"),
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"));
        if (upserted.created()) {
            log.info("Created user {} on first sign-in", upserted.user().id());
        }
        return upserted.user();
    }
}
