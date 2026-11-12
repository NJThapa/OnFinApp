package com.onfinapp.api.watchlist;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.onfinapp.api.user.UserService;
import com.onfinapp.api.watchlist.WatchlistService.WatchlistResponse;

@RestController
@RequestMapping("/api/v1/watchlist")
public class WatchlistController {

    private final WatchlistService watchlist;
    private final UserService users;

    public WatchlistController(WatchlistService watchlist, UserService users) {
        this.watchlist = watchlist;
        this.users = users;
    }

    @GetMapping
    public WatchlistResponse get(@AuthenticationPrincipal Jwt jwt) {
        return watchlist.get(users.resolve(jwt).id());
    }

    @PutMapping("/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void follow(@PathVariable String symbol, @AuthenticationPrincipal Jwt jwt) {
        watchlist.follow(users.resolve(jwt).id(), symbol);
    }

    @DeleteMapping("/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@PathVariable String symbol, @AuthenticationPrincipal Jwt jwt) {
        watchlist.unfollow(users.resolve(jwt).id(), symbol);
    }
}
