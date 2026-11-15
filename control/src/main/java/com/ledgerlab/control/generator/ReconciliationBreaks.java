package com.ledgerlab.control.generator;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

@Schema(description = "Discrepancies between the clients' journal and the statement, by kind, each with its first examples.")
public record ReconciliationBreaks(
    TransferIds acknowledgedNotInStatement,
    TransferIds failedButInStatement,
    TransferIds notInJournal,
    TransferSides payloadMismatch,
    TransferSides statusMismatch
) {

    public ReconciliationBreaks {
        Objects.requireNonNull(acknowledgedNotInStatement, "acknowledgedNotInStatement");
        Objects.requireNonNull(failedButInStatement, "failedButInStatement");
        Objects.requireNonNull(notInJournal, "notInJournal");
        Objects.requireNonNull(payloadMismatch, "payloadMismatch");
        Objects.requireNonNull(statusMismatch, "statusMismatch");
    }
}
