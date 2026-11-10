package com.ledgerlab.generator;

/** Each kind is defined in ADR 0010; any transfer here makes the report unbalanced. */
public record ReconciliationBreaks(
    TransferIds notInJournal,
    TransferIds acknowledgedNotInStatement,
    TransferIds failedButInStatement,
    TransferIds payloadMismatch,
    TransferIds statusMismatch
) {
}
