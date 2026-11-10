package com.ledgerlab.generator;

public class PeriodNotClosedException extends ConflictException {

    public PeriodNotClosedException() {
        super(GeneratorErrors.PERIOD_NOT_CLOSED);
    }
}
