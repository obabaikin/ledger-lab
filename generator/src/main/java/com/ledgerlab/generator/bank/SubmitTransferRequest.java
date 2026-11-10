package com.ledgerlab.generator.bank;

import java.util.Objects;
import java.util.UUID;

public record SubmitTransferRequest(
    UUID transferId,
    UUID runId,
    int fromAccount,
    int toAccount,
    long amountCents
) {
    public SubmitTransferRequest {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(runId, "runId");
    }
}
