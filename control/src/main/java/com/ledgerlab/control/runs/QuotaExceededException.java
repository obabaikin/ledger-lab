package com.ledgerlab.control.runs;

import org.jspecify.annotations.Nullable;

public class QuotaExceededException extends RuntimeException {

    private final @Nullable Long retryAfterSeconds;

    public QuotaExceededException(@Nullable Long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public @Nullable Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
