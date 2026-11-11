package com.ledgerlab.generator.bank;

import java.util.Objects;
import java.util.UUID;

public record SubmitTransferRequest(
    UUID transferId,
    int fromAccount,
    int toAccount,
    long amountCents
) {
    public SubmitTransferRequest {
        Objects.requireNonNull(transferId, "transferId");
    }
}
