package com.onfinapp.api.price;

/** An instrument as the price provider lists it. */
public record ProviderInstrument(String symbol, String name, String type, String exchange) {
}
