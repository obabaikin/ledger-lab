package com.ledgerlab.common;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransferCommand(
    UUID transferId,
    int fromAccount,
    int toAccount,
    long amountCents,
    Instant acceptedAt
) {
    public TransferCommand {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }

    /** Partition key: keeps all transfers of one sender in one partition, so they are booked in the order sent. */
    public String partitionKey() {
        return String.valueOf(fromAccount);
    }
}
