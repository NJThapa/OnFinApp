package com.onfinapp.api.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;

import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.instrument.Instrument;
import com.onfinapp.api.instrument.InstrumentRepository;
import com.onfinapp.api.price.PriceService;
import com.onfinapp.api.price.Quote;
import com.onfinapp.api.watchlist.WatchlistService.WatchlistItemResponse;
import com.onfinapp.api.watchlist.WatchlistService.WatchlistProperties;
import com.onfinapp.api.watchlist.WatchlistService.WatchlistResponse;

class WatchlistServiceTest {

    private static final Instrument APPLE = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "STOCK", "NASDAQ");
    private static final Instrument MICROSOFT =
            new Instrument(UUID.randomUUID(), "MSFT", "Microsoft Corporation", "STOCK", "NASDAQ");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Quote QUOTE = new Quote(10, 1, 11.11, 9, Instant.parse("2026-11-11T17:00:00Z"));

    private final WatchlistRepository watchlist = mock(WatchlistRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final PriceService prices = mock(PriceService.class);
    private final WatchlistService service =
            new WatchlistService(watchlist, instruments, prices, new WatchlistProperties(2));

    @BeforeEach
    void knownInstruments() {
        when(instruments.find(anyString())).thenReturn(Optional.empty());
        when(instruments.find("AAPL")).thenReturn(Optional.of(APPLE));
        when(instruments.find("MSFT")).thenReturn(Optional.of(MICROSOFT));
    }

    @Test
    void listsFollowedInstrumentsWithQuotesAndTheLimit() {
        when(watchlist.list(USER_ID)).thenReturn(List.of(MICROSOFT, APPLE));
        when(prices.quotes(List.of(MICROSOFT.ref(), APPLE.ref()))).thenReturn(Map.of(MICROSOFT.id(), QUOTE));

        WatchlistResponse result = service.get(USER_ID);

        assertThat(result.limit()).isEqualTo(2);
        // An instrument whose quote is unavailable is still listed, without a quote. Ratings come with milestone 3.
        assertThat(result.items()).containsExactly(
                new WatchlistItemResponse("MSFT", "Microsoft Corporation", "STOCK", "NASDAQ", QUOTE, null),
                new WatchlistItemResponse("AAPL", "Apple Inc.", "STOCK", "NASDAQ", null, null));
    }

    @Test
    void followLocksTheUserThenChecksThenInserts() {
        when(watchlist.count(USER_ID)).thenReturn(1);

        service.follow(USER_ID, " aapl ");

        InOrder order = inOrder(watchlist);
        order.verify(watchlist).lockUser(USER_ID);
        order.verify(watchlist).isFollowing(USER_ID, APPLE.id());
        order.verify(watchlist).count(USER_ID);
        order.verify(watchlist).insert(USER_ID, APPLE.id());
    }

    @Test
    void followingBeyondTheLimitIsRefused() {
        when(watchlist.count(USER_ID)).thenReturn(2);

        assertThatThrownBy(() -> service.follow(USER_ID, "AAPL"))
                .isInstanceOf(ApiProblemException.WatchlistLimit.class)
                .hasMessage("You can follow up to 2 instruments.");
        verify(watchlist, never()).insert(any(), any());
    }

    @Test
    void followingAnInstrumentAlreadyFollowedSucceedsAtTheLimit() {
        when(watchlist.isFollowing(USER_ID, APPLE.id())).thenReturn(true);
        when(watchlist.count(USER_ID)).thenReturn(2);

        service.follow(USER_ID, "AAPL");

        verify(watchlist, never()).insert(any(), any());
    }

    @Test
    void unfollowDeletesTheRow() {
        service.unfollow(USER_ID, "msft");

        verify(watchlist).delete(USER_ID, MICROSOFT.id());
    }

    @Test
    void unknownTickerIsNotFound() {
        assertThatThrownBy(() -> service.follow(USER_ID, "NOPE")).isInstanceOf(ApiProblemException.InstrumentNotFound.class);
        assertThatThrownBy(() -> service.unfollow(USER_ID, "NOPE")).isInstanceOf(ApiProblemException.InstrumentNotFound.class);
    }
}
