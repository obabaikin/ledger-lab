package com.ledgerlab.common;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransferCommand(
    UUID runId,
    UUID transferId,
    int fromAccount,
    int toAccount,
    long amountCents,
    Instant createdAt
) {
    public TransferCommand {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Partition key: keeps all transfers of one sender in one partition and isolates runs. */
    public String partitionKey() {
        return runId + ":" + fromAccount;
    }
}
