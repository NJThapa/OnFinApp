package com.onfinapp.api.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.instrument.InstrumentResponses.InstrumentDetailResponse;
import com.onfinapp.api.instrument.InstrumentResponses.InstrumentPageResponse;
import com.onfinapp.api.instrument.InstrumentResponses.InstrumentResponse;
import com.onfinapp.api.instrument.InstrumentResponses.PriceSeriesResponse;
import com.onfinapp.api.price.PricePoint;
import com.onfinapp.api.price.PriceService;
import com.onfinapp.api.price.Quote;
import com.onfinapp.api.watchlist.WatchlistRepository;

class InstrumentServiceTest {

    private static final Instrument APPLE = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "STOCK", "NASDAQ");
    private static final Instrument MICROSOFT =
            new Instrument(UUID.randomUUID(), "MSFT", "Microsoft Corporation", "STOCK", "NASDAQ");
    private static final Instrument SPY =
            new Instrument(UUID.randomUUID(), "SPY", "SPDR S&P 500 ETF Trust", "ETF", "NYSE Arca");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Quote QUOTE = new Quote(10, 1, 11.11, 9, Instant.parse("2026-11-11T17:00:00Z"));

    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final WatchlistRepository watchlist = mock(WatchlistRepository.class);
    private final PriceService prices = mock(PriceService.class);
    private final InstrumentService service = new InstrumentService(instruments, watchlist, prices);

    @BeforeEach
    void knownInstruments() {
        when(instruments.find(anyString())).thenReturn(Optional.empty());
        when(instruments.find("AAPL")).thenReturn(Optional.of(APPLE));
        when(instruments.find("SPY")).thenReturn(Optional.of(SPY));
    }

    private static List<InstrumentMatch> matches(Instrument... found) {
        return List.of(found).stream().map(i -> new InstrumentMatch(i, 2)).toList();
    }

    @Test
    void searchReturnsACursorWhenThereAreMoreResults() {
        when(instruments.search("AA", InstrumentCursor.START, 3)).thenReturn(matches(APPLE, MICROSOFT, SPY));

        // The query is normalised, the search starts at the beginning, and one extra row is requested.
        InstrumentPageResponse page = service.search(" aa ", null, 2);

        assertThat(page.items()).extracting(InstrumentResponse::symbol).containsExactly("AAPL", "MSFT");
        assertThat(page.nextCursor()).isEqualTo(new InstrumentCursor(2, "MSFT").encode());
    }

    @Test
    void searchHasNoCursorOnTheLastPage() {
        when(instruments.search("", InstrumentCursor.START, 4)).thenReturn(matches(APPLE, MICROSOFT, SPY));

        InstrumentPageResponse page = service.search(null, null, 3);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void searchContinuesAfterTheCursor() {
        InstrumentCursor cursor = new InstrumentCursor(1, "AAPL");
        when(instruments.search(anyString(), any(), anyInt())).thenReturn(List.of());

        service.search("a", cursor.encode(), 5);

        verify(instruments).search("A", cursor, 6);
    }

    @Test
    void searchRejectsABadCursor() {
        assertThatThrownBy(() -> service.search("a", "nope", 5)).isInstanceOf(ApiProblemException.InvalidCursor.class);
    }

    @Test
    void detailFindsTheTickerInAnyCaseAndReportsFollowing() {
        when(watchlist.isFollowing(USER_ID, APPLE.id())).thenReturn(true);
        when(prices.quote(APPLE.ref())).thenReturn(Optional.of(QUOTE));

        assertThat(service.detail(" aapl ", USER_ID))
                .isEqualTo(new InstrumentDetailResponse("AAPL", "Apple Inc.", "STOCK", "NASDAQ", QUOTE, true));
    }

    @Test
    void detailHasNoQuoteWhenPricesAreUnavailable() {
        when(prices.quote(SPY.ref())).thenReturn(Optional.empty());

        InstrumentDetailResponse detail = service.detail("SPY", USER_ID);

        assertThat(detail.quote()).isNull();
        assertThat(detail.following()).isFalse();
    }

    @Test
    void unknownTickerIsNotFound() {
        assertThatThrownBy(() -> service.detail("nope", USER_ID))
                .isInstanceOf(ApiProblemException.InstrumentNotFound.class)
                .hasMessage("No instrument with ticker NOPE.");
    }

    @Test
    void pricesCarryTheSymbolAndRange() {
        List<PricePoint> points = List.of(new PricePoint(Instant.parse("2026-11-11T21:00:00Z"), 10));
        when(prices.candles(SPY.ref(), "1Y")).thenReturn(points);

        assertThat(service.prices("spy", "1Y")).isEqualTo(new PriceSeriesResponse("SPY", "1Y", points));
    }

    @Test
    void cursorRoundTrips() {
        InstrumentCursor cursor = new InstrumentCursor(1, "BRK.B");

        assertThat(InstrumentCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void cursorEncodesAsBase64UrlOfRankColonSymbol() {
        // Both APIs must produce the same cursor: base64url("2:AAPL") without padding.
        assertThat(new InstrumentCursor(2, "AAPL").encode()).isEqualTo("MjpBQVBM");
    }

    @ParameterizedTest
    // "AAPL" (no rank), "3:AAPL" (rank out of range), "2:" (no symbol)
    @ValueSource(strings = {"not-a-cursor", "", "!!!", "QUFQTA", "MzpBQVBM", "Mjo"})
    void cursorRejectsAnythingElse(String cursor) {
        assertThatThrownBy(() -> InstrumentCursor.decode(cursor)).isInstanceOf(ApiProblemException.InvalidCursor.class);
    }
}
