package com.ledgerlab.gateway.service;

import java.time.Instant;

public class PeriodNotClosedException extends RuntimeException {

    public PeriodNotClosedException(Instant periodStart) {
        super("The period from " + periodStart + " is not closed");
    }
}
