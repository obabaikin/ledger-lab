package com.ledgerlab.gateway.service;

import java.util.UUID;

public class PeriodNotClosedException extends RuntimeException {

    public PeriodNotClosedException(UUID runId) {
        super("The period of run " + runId + " is not closed");
    }
}
