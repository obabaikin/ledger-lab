package com.ledgerlab.generator;

public class RunStillActiveException extends ConflictException {

    public RunStillActiveException() {
        super(GeneratorErrors.RUN_STILL_ACTIVE);
    }
}
