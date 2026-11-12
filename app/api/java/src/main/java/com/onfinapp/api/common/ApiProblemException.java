package com.onfinapp.api.common;

import org.springframework.http.HttpStatus;

/** An error the client should see. Rendered as an RFC 9457 problem response by {@link ProblemHandler}. */
public abstract class ApiProblemException extends RuntimeException {

    private final HttpStatus status;

    protected ApiProblemException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static final class InstrumentNotFound extends ApiProblemException {
        public InstrumentNotFound(String symbol) {
            super(HttpStatus.NOT_FOUND, "No instrument with ticker " + symbol + ".");
        }
    }

    public static final class InvalidCursor extends ApiProblemException {
        public InvalidCursor() {
            super(HttpStatus.BAD_REQUEST, "cursor is not valid.");
        }
    }

    public static final class WatchlistLimit extends ApiProblemException {
        public WatchlistLimit(int limit) {
            super(HttpStatus.CONFLICT, "You can follow up to " + limit + " instruments.");
        }
    }

    public static final class PricesUnavailable extends ApiProblemException {
        public PricesUnavailable() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "Prices are unavailable right now.");
        }
    }
}
