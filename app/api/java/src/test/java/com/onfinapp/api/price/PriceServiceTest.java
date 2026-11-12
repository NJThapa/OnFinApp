package com.onfinapp.api.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.price.PriceCacheRepository.CachedPayload;

class PriceServiceTest {

    private static final InstrumentRef APPLE = new InstrumentRef(UUID.randomUUID(), "AAPL");
    private static final InstrumentRef MICROSOFT = new InstrumentRef(UUID.randomUUID(), "MSFT");
    private static final Quote STORED = new Quote(5, 0, 0, 5, Instant.parse("2026-11-10T17:00:00Z"));
    private static final Quote FETCHED = new Quote(10, 1, 11.11, 9, Instant.parse("2026-11-11T17:00:00Z"));
    private static final List<PricePoint> POINTS = List.of(new PricePoint(Instant.parse("2026-11-11T21:00:00Z"), 10));

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final PriceCacheRepository cache = mock(PriceCacheRepository.class);
    private final PriceProvider provider = mock(PriceProvider.class);
    private final PriceService service = new PriceService(cache, provider, json);

    @BeforeEach
    void providerAnswers() {
        when(provider.quote(anyString())).thenReturn(FETCHED);
        when(provider.candles(anyString(), anyString())).thenReturn(POINTS);
    }

    private void providerIsDown() {
        when(provider.quote(anyString())).thenThrow(new PriceProviderException("down"));
        when(provider.candles(anyString(), anyString())).thenThrow(new PriceProviderException("down"));
    }

    private CachedPayload payload(Object value, boolean fresh) throws JsonProcessingException {
        return new CachedPayload(json.writeValueAsString(value), fresh);
    }

    private void storedQuote(boolean fresh) throws JsonProcessingException {
        when(cache.readQuotes(any(), eq(PriceService.QUOTE_TTL_SECONDS)))
                .thenReturn(Map.of(APPLE.id(), payload(STORED, fresh)));
    }

    @Test
    void freshQuoteComesFromTheCacheWithoutCallingTheProvider() throws Exception {
        storedQuote(true);

        assertThat(service.quote(APPLE)).contains(STORED);
        verify(provider, never()).quote(anyString());
    }

    @Test
    void missingQuoteIsFetchedAndCached() throws Exception {
        assertThat(service.quote(APPLE)).contains(FETCHED);
        verify(cache).write(APPLE.id(), PriceCacheRepository.QUOTE_KEY, json.writeValueAsString(FETCHED));
    }

    @Test
    void staleQuoteIsRefreshed() throws Exception {
        storedQuote(false);

        assertThat(service.quote(APPLE)).contains(FETCHED);
    }

    @Test
    void staleQuoteIsServedWhenTheProviderIsDown() throws Exception {
        storedQuote(false);
        providerIsDown();

        assertThat(service.quote(APPLE)).contains(STORED);
    }

    @Test
    void noQuoteWhenTheProviderIsDownAndNothingIsCached() {
        providerIsDown();

        assertThat(service.quote(APPLE)).isEmpty();
    }

    @Test
    void quotesForSeveralInstrumentsSkipTheOnesThatFail() throws Exception {
        storedQuote(true);
        providerIsDown();

        assertThat(service.quotes(List.of(APPLE, MICROSOFT))).containsOnlyKeys(APPLE.id());
    }

    @Test
    void freshCandlesComeFromTheCache() throws Exception {
        when(cache.read(APPLE.id(), PriceRanges.ONE_MONTH, null)).thenReturn(Optional.of(payload(POINTS, true)));

        assertThat(service.candles(APPLE, PriceRanges.ONE_MONTH)).isEqualTo(POINTS);
        verify(provider, never()).candles(anyString(), anyString());
    }

    @Test
    void dailyRangesAreFreshForTheDayAndIntradayRangesForFiveMinutes() {
        when(cache.read(any(), anyString(), any())).thenReturn(Optional.empty());

        service.candles(APPLE, PriceRanges.ONE_YEAR);
        service.candles(APPLE, PriceRanges.ONE_DAY);

        verify(cache).read(APPLE.id(), PriceRanges.ONE_YEAR, null);
        verify(cache).read(APPLE.id(), PriceRanges.ONE_DAY, PriceService.INTRADAY_TTL_SECONDS);
    }

    @Test
    void missingCandlesAreFetchedAndCachedUnderTheirRange() throws Exception {
        when(cache.read(any(), anyString(), any())).thenReturn(Optional.empty());

        assertThat(service.candles(APPLE, PriceRanges.ONE_YEAR)).isEqualTo(POINTS);
        verify(cache).write(APPLE.id(), PriceRanges.ONE_YEAR, json.writeValueAsString(POINTS));
    }

    @Test
    void staleCandlesAreServedWhenTheProviderIsDown() throws Exception {
        when(cache.read(APPLE.id(), PriceRanges.ONE_DAY, PriceService.INTRADAY_TTL_SECONDS))
                .thenReturn(Optional.of(payload(POINTS, false)));
        providerIsDown();

        assertThat(service.candles(APPLE, PriceRanges.ONE_DAY)).isEqualTo(POINTS);
    }

    @Test
    void candlesAreUnavailableWhenTheProviderIsDownAndNothingIsCached() {
        when(cache.read(any(), anyString(), any())).thenReturn(Optional.empty());
        providerIsDown();

        assertThatThrownBy(() -> service.candles(APPLE, PriceRanges.ONE_DAY))
                .isInstanceOf(ApiProblemException.PricesUnavailable.class);
    }

    @Test
    void cachedPayloadsUseTheSameJsonAsTheDotNetApi() throws Exception {
        // Both APIs read each other's cache entries, so the stored form is part of the contract between them.
        assertThat(json.writeValueAsString(FETCHED)).isEqualTo(
                "{\"price\":10.0,\"change\":1.0,\"changePercent\":11.11,\"previousClose\":9.0,\"asOf\":\"2026-11-11T17:00:00Z\"}");
        assertThat(json.writeValueAsString(POINTS)).isEqualTo("[{\"time\":\"2026-11-11T21:00:00Z\",\"close\":10.0}]");
    }
}
