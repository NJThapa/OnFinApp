package com.onfinapp.api.price;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deterministic invented prices; no network. The formulas are specified in shared/mock/README.md
 * and implemented identically in the .NET API.
 */
public class MockPriceProvider implements PriceProvider {

    /** One entry of shared/mock/instruments.json. */
    public record MockInstrument(String symbol, String name, String type, String exchange, double basePrice) {
    }

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final long SECONDS_PER_DAY = 86400;

    private final Map<String, MockInstrument> fixtures = new LinkedHashMap<>();
    private final Clock clock;

    public MockPriceProvider(List<MockInstrument> instruments, Clock clock) {
        instruments.forEach(i -> fixtures.put(i.symbol(), i));
        this.clock = clock;
    }

    public static MockPriceProvider fromSharedFolder(String sharedDir, ObjectMapper json, Clock clock) {
        Path file = Path.of(sharedDir, "mock", "instruments.json");
        try {
            return new MockPriceProvider(json.readValue(file.toFile(), new TypeReference<List<MockInstrument>>() {
            }), clock);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the mock instrument list at " + file.toAbsolutePath(), e);
        }
    }

    @Override
    public List<ProviderInstrument> listInstruments() {
        return fixtures.values().stream()
                .map(f -> new ProviderInstrument(f.symbol(), f.name(), f.type(), f.exchange()))
                .toList();
    }

    @Override
    public Quote quote(String symbol) {
        Series series = seriesFor(symbol);
        long day = lastTradingDay(today());
        double price = series.close(day);
        double previous = series.close(previousTradingDay(day));
        double change = round2(price - previous);
        return new Quote(price, change, round2(change / previous * 100), previous,
                Instant.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    @Override
    public List<PricePoint> candles(String symbol, String range) {
        Series series = seriesFor(symbol);
        long today = today();
        List<PricePoint> points = new ArrayList<>();
        switch (range) {
            case PriceRanges.ONE_DAY -> series.intraday(lastTradingDay(today), 79, 5, points);
            case PriceRanges.ONE_WEEK -> {
                List<Long> days = new ArrayList<>();
                for (long d = lastTradingDay(today); days.size() < 5; d = previousTradingDay(d)) {
                    days.add(0, d);
                }
                days.forEach(d -> series.intraday(d, 7, 60, points));
            }
            case PriceRanges.ONE_MONTH -> series.daily(today - 30, today, false, points);
            case PriceRanges.ONE_YEAR -> series.daily(today - 365, today, false, points);
            case PriceRanges.FIVE_YEARS -> series.daily(today - 1825, today, true, points);
            default -> throw new IllegalArgumentException("Unknown range " + range);
        }
        return points;
    }

    private Series seriesFor(String symbol) {
        MockInstrument fixture = fixtures.get(symbol);
        if (fixture == null) {
            throw new PriceProviderException("No mock prices for " + symbol);
        }
        return new Series(symbol, fixture.basePrice());
    }

    private static final class Series {
        private final double basePrice;
        private final long hash;
        private final long phase;

        Series(String symbol, double basePrice) {
            this.basePrice = basePrice;
            long h = 7;
            for (int i = 0; i < symbol.length(); i++) {
                h = (h * 31 + symbol.charAt(i)) % 100003;
            }
            this.hash = h;
            this.phase = h % 365;
        }

        double close(long day) {
            double noise = (((hash * 131 + day * 7919) % 2001) - 1000) / 1000.0;
            double x = day + phase;
            return round2(basePrice * (1
                    + 0.15 * Math.sin(2 * Math.PI * x / 365)
                    + 0.04 * Math.sin(2 * Math.PI * x / 29)
                    + 0.01 * noise));
        }

        void daily(long from, long to, boolean fridaysOnly, List<PricePoint> out) {
            for (long d = from; d <= to; d++) {
                if (fridaysOnly ? weekday(d) == 4 : weekday(d) < 5) {
                    out.add(new PricePoint(Instant.ofEpochSecond(d * SECONDS_PER_DAY + 21 * 3600), close(d)));
                }
            }
        }

        void intraday(long day, int count, int stepMinutes, List<PricePoint> out) {
            double close = close(day);
            double previous = close(previousTradingDay(day));
            long open = day * SECONDS_PER_DAY + 14 * 3600 + 30 * 60;
            for (int k = 0; k < count; k++) {
                double t = (double) k / (count - 1);
                double value = previous + (close - previous) * t
                        + basePrice * 0.004 * Math.sin(0.9 * k + phase) * Math.sin(Math.PI * t);
                out.add(new PricePoint(Instant.ofEpochSecond(open + (long) k * stepMinutes * 60), round2(value)));
            }
        }
    }

    /** The current New York calendar day, in days since 1970-01-01. The same day boundary as the price cache. */
    private long today() {
        return LocalDate.now(clock.withZone(NEW_YORK)).toEpochDay();
    }

    /** 0 = Monday ... 6 = Sunday. Day 0 (1970-01-01) was a Thursday. */
    private static int weekday(long day) {
        return (int) ((day + 3) % 7);
    }

    private static long lastTradingDay(long day) {
        while (weekday(day) >= 5) {
            day--;
        }
        return day;
    }

    private static long previousTradingDay(long day) {
        return lastTradingDay(day - 1);
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
