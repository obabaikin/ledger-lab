package com.ledgerlab.generator;

import io.swagger.v3.oas.annotations.media.Schema;

/** Each kind is defined in ADR 0010; any transfer here makes the report unbalanced. */
public record ReconciliationBreaks(
    @Schema(description = "In the bank's statement, not in the client journal.")
    TransferIds notInJournal,
    @Schema(description = "The bank told the client it accepted the transfer in this period; its statement does not list it.")
    TransferIds acknowledgedNotInStatement,
    @Schema(description = "The client was told the bank did not take it; the statement lists it.")
    TransferIds failedButInStatement,
    @Schema(description = "Accounts or amount differ.")
    TransferSides payloadMismatch,
    @Schema(description = "The final status or the reject reason the client was told differs from the statement's.")
    TransferSides statusMismatch
) {
    boolean none() {
        return notInJournal.count() == 0 && acknowledgedNotInStatement.count() == 0 && failedButInStatement.count() == 0
            && payloadMismatch.count() == 0 && statusMismatch.count() == 0;
    }
}
