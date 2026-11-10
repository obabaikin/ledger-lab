package com.ledgerlab.generator.bank;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record TransferResponse(
    UUID transferId,
    UUID runId,
    TransferStatus status,
    @Nullable RejectReason rejectReason
) {
    public static final String REJECT_REASON_FIELD = "rejectReason";

    public TransferResponse {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
    }
}
