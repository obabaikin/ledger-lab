package com.ledgerlab.control.load;

public class LoadRunningException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoadRunningException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
