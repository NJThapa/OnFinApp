package com.onfinapp.api.instrument;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.onfinapp.api.price.PriceConfig.PriceProperties;
import com.onfinapp.api.price.PriceProvider;
import com.onfinapp.api.price.ProviderInstrument;

/** Copies the price provider's instrument list into the instruments table at startup and then on an interval. */
@Component
public class InstrumentSync {

    private static final Logger log = LoggerFactory.getLogger(InstrumentSync.class);

    private final PriceProvider provider;
    private final InstrumentRepository instruments;
    private final Clock clock;
    private final Duration interval;
    private Instant lastSuccess;

    public InstrumentSync(PriceProvider provider, InstrumentRepository instruments, PriceProperties properties, Clock clock) {
        this.provider = provider;
        this.instruments = instruments;
        this.clock = clock;
        this.interval = Duration.ofHours(properties.instrumentSyncIntervalHours());
    }

    /** Checks every minute, so a failed sync (database or provider down) is retried soon. */
    @Scheduled(fixedDelay = 60_000)
    void tick() {
        Instant now = clock.instant();
        if (lastSuccess != null && now.isBefore(lastSuccess.plus(interval))) {
            return;
        }
        try {
            List<ProviderInstrument> list = provider.listInstruments();
            instruments.upsert(list);
            lastSuccess = now;
            log.info("Synced {} instruments from the price provider", list.size());
        } catch (RuntimeException e) {
            log.warn("Instrument sync failed, retrying in a minute: {}", e.getMessage());
        }
    }
}
