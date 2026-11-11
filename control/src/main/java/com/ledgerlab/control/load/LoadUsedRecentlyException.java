package com.ledgerlab.control.load;

public class LoadUsedRecentlyException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoadUsedRecentlyException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
