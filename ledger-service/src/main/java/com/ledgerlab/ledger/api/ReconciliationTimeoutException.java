package com.ledgerlab.ledger.api;

import java.util.UUID;

public class ReconciliationTimeoutException extends RuntimeException {

    private final UUID runId;

    public ReconciliationTimeoutException(UUID runId, Throwable cause) {
        super(cause);
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}
