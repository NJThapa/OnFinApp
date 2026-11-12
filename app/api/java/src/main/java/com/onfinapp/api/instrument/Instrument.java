package com.onfinapp.api.instrument;

import java.util.UUID;

import com.onfinapp.api.price.InstrumentRef;

public record Instrument(UUID id, String symbol, String name, String type, String exchange) {

    public InstrumentRef ref() {
        return new InstrumentRef(id, symbol);
    }

    /** Tickers are stored in upper case; lookups are case-insensitive. */
    public static String normalize(String symbol) {
        return symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
