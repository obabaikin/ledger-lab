package com.ledgerlab.ledger.service;

import java.util.UUID;

public class RunAlreadyExistsException extends RuntimeException {

    public RunAlreadyExistsException(UUID runId) {
        super("Run " + runId + " already has accounts");
    }
}
