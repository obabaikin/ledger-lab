package com.ledgerlab.ledger.domain;

import com.ledgerlab.common.RejectReason;

public sealed interface TransferResult {

    record Posted() implements TransferResult {
    }

    record Rejected(RejectReason reason) implements TransferResult {
    }

    record AlreadyProcessed() implements TransferResult {
    }

    /**
     * Accepted so long ago that the ledger no longer holds its period: whether it was booked then cannot be told any
     * more, and booking it again could book it twice (ADR 0022). Nothing is recorded for it.
     */
    record Expired() implements TransferResult {
    }
}
