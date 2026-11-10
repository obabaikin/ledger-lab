package com.ledgerlab.gateway.service;

import java.util.UUID;

public record TransferRequest(
    UUID transferId,
    int fromAccount,
    int toAccount,
    long amountCents
) {
}
