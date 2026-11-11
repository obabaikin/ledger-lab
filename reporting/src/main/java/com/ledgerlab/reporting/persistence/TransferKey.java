package com.ledgerlab.reporting.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.UUID;

/** A transfer's row is found by both: the time names its partition. To the microsecond, as the database keeps it. */
public record TransferKey(Instant acceptedAt, UUID transferId) implements Comparable<TransferKey> {

    private static final Comparator<TransferKey> ORDER = Comparator.comparing(TransferKey::acceptedAt).thenComparing(TransferKey::transferId);

    public TransferKey {
        acceptedAt = acceptedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public int compareTo(TransferKey other) {
        return ORDER.compare(this, other);
    }
}
