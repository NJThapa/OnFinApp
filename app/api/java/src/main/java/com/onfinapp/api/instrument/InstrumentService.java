package com.onfinapp.api.instrument;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.onfinapp.api.common.ApiProblemException;
import com.onfinapp.api.instrument.InstrumentResponses.InstrumentDetailResponse;
import com.onfinapp.api.instrument.InstrumentResponses.InstrumentPageResponse;
import com.onfinapp.api.instrument.InstrumentResponses.InstrumentResponse;
import com.onfinapp.api.instrument.InstrumentResponses.PriceSeriesResponse;
import com.onfinapp.api.price.PriceService;
import com.onfinapp.api.price.Quote;
import com.onfinapp.api.watchlist.WatchlistRepository;

@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    private final InstrumentRepository instruments;
    private final WatchlistRepository watchlist;
    private final PriceService prices;

    public InstrumentService(InstrumentRepository instruments, WatchlistRepository watchlist, PriceService prices) {
        this.instruments = instruments;
        this.watchlist = watchlist;
        this.prices = prices;
    }

    /** @throws ApiProblemException.InstrumentNotFound when no instrument has this ticker */
    public Instrument get(String symbol) {
        String normalized = Instrument.normalize(symbol);
        return instruments.find(normalized).orElseThrow(() -> new ApiProblemException.InstrumentNotFound(normalized));
    }

    public InstrumentPageResponse search(String query, String cursor, int limit) {
        InstrumentCursor after =
                cursor == null || cursor.isEmpty() ? InstrumentCursor.START : InstrumentCursor.decode(cursor);

        // One extra row tells whether there is a next page.
        List<InstrumentMatch> matches =
                instruments.search(Instrument.normalize(query == null ? "" : query), after, limit + 1);
        List<InstrumentMatch> page = matches.size() > limit ? matches.subList(0, limit) : matches;
        String next = null;
        if (matches.size() > limit) {
            InstrumentMatch last = page.get(page.size() - 1);
            next = new InstrumentCursor(last.rank(), last.instrument().symbol()).encode();
        }
        log.debug("Instrument search returned {} items, more: {}", page.size(), next != null);
        return new InstrumentPageResponse(
                page.stream().map(m -> InstrumentResponse.from(m.instrument())).toList(), next);
    }

    public InstrumentDetailResponse detail(String symbol, UUID userId) {
        Instrument instrument = get(symbol);
        boolean following = watchlist.isFollowing(userId, instrument.id());
        Quote quote = prices.quote(instrument.ref()).orElse(null);
        return new InstrumentDetailResponse(
                instrument.symbol(), instrument.name(), instrument.type(), instrument.exchange(), quote, following);
    }

    public PriceSeriesResponse prices(String symbol, String range) {
        Instrument instrument = get(symbol);
        return new PriceSeriesResponse(instrument.symbol(), range, prices.candles(instrument.ref(), range));
    }
}
