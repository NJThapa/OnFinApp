package com.onfinapp.api.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/api/v1/me")
    public User me(@AuthenticationPrincipal Jwt jwt) {
        return users.resolve(jwt);
    }
}
