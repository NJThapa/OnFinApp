package com.onfinapp.api.price;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.price.PriceCacheRepository.CachedPayload;

import jakarta.annotation.PreDestroy;

/**
 * Quotes and chart history, read through the shared cache.
 * Quotes are fresh for 60 seconds, intraday charts for 5 minutes, daily charts until the next New York day.
 * When the provider fails, the stale entry is served if there is one.
 */
@Service
public class PriceService {

    public static final int QUOTE_TTL_SECONDS = 60;
    public static final int INTRADAY_TTL_SECONDS = 300;
    private static final int MAX_CONCURRENT_FETCHES = 8;

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);

    private final PriceCacheRepository cache;
    private final PriceProvider provider;
    private final ObjectMapper json;
    private final JavaType pointList;
    private final ExecutorService fetchers = Executors.newFixedThreadPool(MAX_CONCURRENT_FETCHES);

    public PriceService(PriceCacheRepository cache, PriceProvider provider, ObjectMapper json) {
        this.cache = cache;
        this.provider = provider;
        this.json = json;
        this.pointList = json.getTypeFactory().constructCollectionType(List.class, PricePoint.class);
    }

    @PreDestroy
    void shutdown() {
        fetchers.shutdown();
    }

    /** Empty when the provider is unavailable and nothing is cached. */
    public Optional<Quote> quote(InstrumentRef instrument) {
        return Optional.ofNullable(quotes(List.of(instrument)).get(instrument.id()));
    }

    /** Quotes for several instruments; instruments without a quote are absent from the result. */
    public Map<UUID, Quote> quotes(List<InstrumentRef> instruments) {
        Map<UUID, CachedPayload> cached =
                cache.readQuotes(instruments.stream().map(InstrumentRef::id).toList(), QUOTE_TTL_SECONDS);

        Map<UUID, Quote> result = new HashMap<>();
        Map<InstrumentRef, Future<Quote>> pending = new LinkedHashMap<>();
        for (InstrumentRef instrument : instruments) {
            CachedPayload hit = cached.get(instrument.id());
            if (hit != null && hit.fresh()) {
                result.put(instrument.id(), parse(hit.json(), json.constructType(Quote.class)));
            } else {
                log.debug("Quote for {} is not cached or stale; asking the price provider", instrument.symbol());
                pending.put(instrument, fetchers.submit(() -> provider.quote(instrument.symbol())));
            }
        }
        pending.forEach((instrument, future) -> {
            try {
                Quote quote = await(future);
                cache.write(instrument.id(), PriceCacheRepository.QUOTE_KEY, serialize(quote));
                result.put(instrument.id(), quote);
            } catch (PriceProviderException e) {
                CachedPayload stale = cached.get(instrument.id());
                log.warn("Price provider failed for the {} quote ({}); serving {}",
                        instrument.symbol(), e.getMessage(), stale == null ? "no quote" : "the stale cached quote");
                if (stale != null) {
                    result.put(instrument.id(), parse(stale.json(), json.constructType(Quote.class)));
                }
            }
        });
        return result;
    }

    /** @throws ApiProblemException.PricesUnavailable when the provider fails and nothing is cached */
    public List<PricePoint> candles(InstrumentRef instrument, String range) {
        Integer ttl = PriceRanges.isIntraday(range) ? INTRADAY_TTL_SECONDS : null;
        Optional<CachedPayload> hit = cache.read(instrument.id(), range, ttl);
        if (hit.isPresent() && hit.get().fresh()) {
            return parse(hit.get().json(), pointList);
        }
        try {
            log.debug("{} prices for {} are not cached or stale; asking the price provider", range, instrument.symbol());
            List<PricePoint> points = provider.candles(instrument.symbol(), range);
            cache.write(instrument.id(), range, serialize(points));
            return points;
        } catch (PriceProviderException e) {
            log.warn("Price provider failed for {} {} prices ({}); serving {}",
                    instrument.symbol(), range, e.getMessage(), hit.isPresent() ? "the stale cached prices" : "an error");
            if (hit.isPresent()) {
                return parse(hit.get().json(), pointList);
            }
            throw new ApiProblemException.PricesUnavailable();
        }
    }

    /** Waits for a provider call, rethrowing its failure as it was thrown. */
    private static Quote await(Future<Quote> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PriceProviderException("Interrupted while waiting for the price provider", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private String serialize(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize a price payload", e);
        }
    }

    private <T> T parse(String payload, JavaType type) {
        try {
            return json.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The price cache holds an unreadable entry", e);
        }
    }
}
