package com.ledgerlab.gateway.service;

import java.time.Instant;

public class UnknownPeriodException extends RuntimeException {

    public UnknownPeriodException(Instant periodStart, String why) {
        super("No period held by the gateway starts at " + periodStart + ": " + why);
    }
}
