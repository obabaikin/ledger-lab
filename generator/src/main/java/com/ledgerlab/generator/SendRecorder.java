package com.ledgerlab.generator;

import org.jspecify.annotations.Nullable;

/** Receives every send of one submission as it ends, so a send cut short by cancellation is still recorded. */
@FunctionalInterface
public interface SendRecorder {

    SendRecorder NONE = (attempt, startedNanos, endedNanos, result, httpStatus) -> {
    };

    void record(int attempt, long startedNanos, long endedNanos, SendResult result, @Nullable Integer httpStatus);
}
