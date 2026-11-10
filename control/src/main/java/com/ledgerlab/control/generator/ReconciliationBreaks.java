package com.ledgerlab.control.generator;

import java.util.Objects;

public record ReconciliationBreaks(
    TransferIds notInJournal,
    TransferIds acknowledgedNotInStatement,
    TransferIds failedButInStatement,
    TransferIds payloadMismatch,
    TransferIds statusMismatch
) {
    public ReconciliationBreaks {
        Objects.requireNonNull(notInJournal, "notInJournal");
        Objects.requireNonNull(acknowledgedNotInStatement, "acknowledgedNotInStatement");
        Objects.requireNonNull(failedButInStatement, "failedButInStatement");
        Objects.requireNonNull(payloadMismatch, "payloadMismatch");
        Objects.requireNonNull(statusMismatch, "statusMismatch");
    }
}
