package com.onfinapp.api.instrument;

import java.util.List;

import com.onfinapp.api.price.PricePoint;
import com.onfinapp.api.price.Quote;

/** Response bodies, as defined in the shared contract. */
public final class InstrumentResponses {

    private InstrumentResponses() {
    }

    public record InstrumentResponse(String symbol, String name, String type, String exchange) {
        public static InstrumentResponse from(Instrument i) {
            return new InstrumentResponse(i.symbol(), i.name(), i.type(), i.exchange());
        }
    }

    public record InstrumentPageResponse(List<InstrumentResponse> items, String nextCursor) {
    }

    public record InstrumentDetailResponse(
            String symbol, String name, String type, String exchange, Quote quote, boolean following) {
    }

    public record PriceSeriesResponse(String symbol, String range, List<PricePoint> points) {
    }
}
