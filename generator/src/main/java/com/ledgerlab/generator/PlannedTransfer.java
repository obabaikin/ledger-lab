package com.ledgerlab.generator;

import java.util.UUID;

/** A transfer the simulated client is about to make; its id is the idempotency key. */
public record PlannedTransfer(
    UUID runId,
    UUID transferId,
    int fromAccount,
    int toAccount,
    long amountCents
) {
}
