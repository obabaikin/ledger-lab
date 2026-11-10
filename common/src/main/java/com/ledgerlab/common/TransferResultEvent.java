package com.ledgerlab.common;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record TransferResultEvent(
    UUID runId,
    UUID transferId,
    TransferOutcome outcome,
    @Nullable RejectReason rejectReason,
    Instant processedAt
) {
    public TransferResultEvent {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(processedAt, "processedAt");
    }

    public String partitionKey() {
        return partitionKey(runId, transferId);
    }

    public static String partitionKey(UUID runId, UUID transferId) {
        return runId + ":" + transferId;
    }
}
