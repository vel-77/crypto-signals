package com.cryptosignals.exchange;

/** Any failure talking to an exchange. Carries the venue's own status code when there was one. */
public class ExchangeException extends RuntimeException {

    private final int statusCode;
    private final boolean retryable;

    public ExchangeException(String message, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public ExchangeException(String message, Throwable cause) {
        this(message, -1, true, cause);
    }

    public int statusCode() {
        return statusCode;
    }

    /** Whether retrying the same call could plausibly succeed. */
    public boolean retryable() {
        return retryable;
    }
}
