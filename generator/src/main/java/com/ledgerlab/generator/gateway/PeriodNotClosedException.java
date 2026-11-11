package com.ledgerlab.generator.gateway;

public class PeriodNotClosedException extends RuntimeException {

    public PeriodNotClosedException(String message) {
        super(message);
    }
}
