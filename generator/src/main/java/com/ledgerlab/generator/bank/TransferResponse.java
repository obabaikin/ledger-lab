package com.ledgerlab.generator.bank;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record TransferResponse(
    UUID transferId,
    TransferStatus status,
    @Nullable RejectReason rejectReason,
    // The bank's clock: it places the transfer in a period.
    Instant acceptedAt
) {
    public static final String REJECT_REASON_FIELD = "rejectReason";

    public TransferResponse {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
