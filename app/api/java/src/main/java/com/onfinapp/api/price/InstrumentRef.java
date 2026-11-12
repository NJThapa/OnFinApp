package com.onfinapp.api.price;

import java.util.UUID;

/** What the price services need to know about an instrument. */
public record InstrumentRef(UUID id, String symbol) {
}
