package com.ledgerlab.control.entry;

public class DailyLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public DailyLimitException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
