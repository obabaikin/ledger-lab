package com.ledgerlab.gateway.service;

import java.util.UUID;

public class PeriodClosedException extends RuntimeException {

    public PeriodClosedException(UUID runId) {
        super("The period of run " + runId + " is closed");
    }
}
