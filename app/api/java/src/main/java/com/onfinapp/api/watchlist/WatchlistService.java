package com.onfinapp.api.watchlist;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.instrument.Instrument;
import com.onfinapp.api.instrument.InstrumentRepository;
import com.onfinapp.api.price.PriceService;
import com.onfinapp.api.price.Quote;

import jakarta.validation.constraints.Positive;

@Service
@EnableConfigurationProperties(WatchlistService.WatchlistProperties.class)
public class WatchlistService {

    /** @param limit maximum number of instruments one user can follow (watchlist.limit) */
    @Validated
    @ConfigurationProperties("watchlist")
    public record WatchlistProperties(@Positive int limit) {
    }

    /** rating is null until analyses exist (milestone 3). */
    public record WatchlistItemResponse(
            String symbol, String name, String type, String exchange, Quote quote, String rating) {
    }

    public record WatchlistResponse(List<WatchlistItemResponse> items, int limit) {
    }

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    private final WatchlistRepository watchlist;
    private final InstrumentRepository instruments;
    private final PriceService prices;
    private final int limit;

    public WatchlistService(WatchlistRepository watchlist, InstrumentRepository instruments, PriceService prices,
            WatchlistProperties properties) {
        this.watchlist = watchlist;
        this.instruments = instruments;
        this.prices = prices;
        this.limit = properties.limit();
    }

    public WatchlistResponse get(UUID userId) {
        List<Instrument> followed = watchlist.list(userId);
        Map<UUID, Quote> quotes = prices.quotes(followed.stream().map(Instrument::ref).toList());
        return new WatchlistResponse(
                followed.stream()
                        .map(i -> new WatchlistItemResponse(
                                i.symbol(), i.name(), i.type(), i.exchange(), quotes.get(i.id()), null))
                        .toList(),
                limit);
    }

    /**
     * Idempotent.
     *
     * @throws ApiProblemException.WatchlistLimit when the user is at the limit
     */
    @Transactional
    public void follow(UUID userId, String symbol) {
        Instrument instrument = require(symbol);
        watchlist.lockUser(userId);
        if (watchlist.isFollowing(userId, instrument.id())) {
            return;
        }
        if (watchlist.count(userId) >= limit) {
            log.info("User {} is at the watchlist limit of {}; {} not followed", userId, limit, instrument.symbol());
            throw new ApiProblemException.WatchlistLimit(limit);
        }
        watchlist.insert(userId, instrument.id());
        log.info("User {} followed {}", userId, instrument.symbol());
    }

    /** Idempotent. */
    public void unfollow(UUID userId, String symbol) {
        Instrument instrument = require(symbol);
        watchlist.delete(userId, instrument.id());
        log.info("User {} unfollowed {}", userId, instrument.symbol());
    }

    private Instrument require(String symbol) {
        String normalized = Instrument.normalize(symbol);
        return instruments.find(normalized).orElseThrow(() -> new ApiProblemException.InstrumentNotFound(normalized));
    }
}
