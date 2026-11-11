package com.ledgerlab.generator;

/** Receives every send of one submission as it ends, so a send cut short by cancellation is still recorded. */
@FunctionalInterface
public interface SendRecorder {

    SendRecorder NONE = (attempt, startedNanos, endedNanos, kind, finalStatus) -> {
    };

    /** {@code finalStatus}: the bank answered 200, the transfer's final status, not 202. */
    void record(int attempt, long startedNanos, long endedNanos, ResponseKind kind, boolean finalStatus);
}
