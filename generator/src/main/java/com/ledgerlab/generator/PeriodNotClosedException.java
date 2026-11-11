package com.ledgerlab.generator;

public class PeriodNotClosedException extends RuntimeException {

    public PeriodNotClosedException(String message) {
        super(message);
    }
}
