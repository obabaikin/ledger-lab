package com.ledgerlab.reporting.persistence;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What one event added to the numbers of the second its transfer was accepted in. An event that changed no row, a
 * redelivered one, has no change.
 */
public record StatsChange(
    Instant acceptedAt,
    boolean accepted,
    boolean commandSeen,
    boolean posted,
    boolean rejected,
    long postedAmountCents,
    @Nullable String rejectReason
) {
}
