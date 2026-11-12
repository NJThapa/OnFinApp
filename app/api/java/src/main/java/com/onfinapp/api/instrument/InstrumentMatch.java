package com.onfinapp.api.instrument;

/** A search hit. Rank 0: exact ticker, 1: ticker starts with the query, 2: name contains it (or no query). */
public record InstrumentMatch(Instrument instrument, int rank) {
}
