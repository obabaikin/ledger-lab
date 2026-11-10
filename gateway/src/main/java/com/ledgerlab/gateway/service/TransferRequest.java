package com.ledgerlab.gateway.service;

import java.util.UUID;

public record TransferRequest(
    UUID transferId,
    UUID runId,
    int fromAccount,
    int toAccount,
    long amountCents
) {
}
