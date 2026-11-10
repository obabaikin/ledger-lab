package com.ledgerlab.control.chaos;

public class ChaosUsedRecentlyException extends RuntimeException {

    private final long retryAfterSeconds;

    public ChaosUsedRecentlyException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
