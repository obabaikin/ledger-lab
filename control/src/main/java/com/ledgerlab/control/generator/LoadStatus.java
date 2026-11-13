package com.ledgerlab.control.generator;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The part of the generator's load status control shows; the counts run from the generator's start, the total from the bank's. */
public record LoadStatus(
    @Nullable Instant startedAt,
    int baseTps,
    int targetTps,
    @Nullable Pulse pulse,
    long requested,
    long acknowledged,
    long failed,
    long unknown,
    long retries,
    long refused,
    long inFlight,
    @Nullable ClientTotals total,
    boolean restarted
) {
}
