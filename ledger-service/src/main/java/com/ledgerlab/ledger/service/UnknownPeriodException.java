package com.ledgerlab.ledger.service;

import java.time.Instant;

public class UnknownPeriodException extends RuntimeException {

    public UnknownPeriodException(Instant periodStart) {
        super("No ended period held by the ledger starts at " + periodStart);
    }
}
