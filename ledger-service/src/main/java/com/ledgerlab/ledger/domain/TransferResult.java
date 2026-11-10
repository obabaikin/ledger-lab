package com.ledgerlab.ledger.domain;

import com.ledgerlab.common.RejectReason;

public sealed interface TransferResult {

    record Posted() implements TransferResult {
    }

    record Rejected(RejectReason reason) implements TransferResult {
    }

    record AlreadyProcessed() implements TransferResult {
    }
}
