package com.onfinapp.api.price;

import java.util.List;

/**
 * Source of the instrument list, quotes and chart history. Chosen with PRICE_PROVIDER.
 * Implementations throw {@link PriceProviderException} when they cannot answer.
 */
public interface PriceProvider {

    List<ProviderInstrument> listInstruments();

    Quote quote(String symbol);

    /** Closing prices for one of {@link PriceRanges}, oldest first. */
    List<PricePoint> candles(String symbol, String range);
}
