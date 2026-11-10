package com.ledgerlab.gateway.service;

import java.util.UUID;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(UUID transferId) {
        super("Transfer " + transferId + " was already submitted with a different payload");
    }
}
