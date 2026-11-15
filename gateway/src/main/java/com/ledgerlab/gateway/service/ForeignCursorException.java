package com.ledgerlab.gateway.service;

import java.time.Instant;

public class ForeignCursorException extends RuntimeException {

    public ForeignCursorException(Instant periodStart) {
        super("The cursor is not of the period from " + periodStart);
    }
}
