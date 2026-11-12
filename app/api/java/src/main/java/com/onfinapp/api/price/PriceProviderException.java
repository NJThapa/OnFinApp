package com.onfinapp.api.price;

/** Thrown by a price provider that cannot answer. */
public class PriceProviderException extends RuntimeException {

    public PriceProviderException(String message) {
        super(message);
    }

    public PriceProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
