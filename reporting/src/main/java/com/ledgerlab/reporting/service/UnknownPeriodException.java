package com.ledgerlab.reporting.service;

import java.time.Instant;

public class UnknownPeriodException extends RuntimeException {

    public UnknownPeriodException(Instant periodStart, String why) {
        super("No period held by reporting starts at " + periodStart + ": " + why);
    }
}
