package com.ledgerlab.common;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record TransferResultEvent(
    UUID transferId,
    TransferOutcome outcome,
    @Nullable RejectReason rejectReason,
    Instant processedAt,
    Instant acceptedAt
) {
    public TransferResultEvent {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(processedAt, "processedAt");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }

    public String partitionKey() {
        return transferId.toString();
    }
}
