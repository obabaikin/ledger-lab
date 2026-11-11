package com.ledgerlab.generator;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "The client journal against the bank's statement of one closed period.")
public record ClientReconciliationReport(
    Instant periodStart,
    @Schema(description = "No break of any kind.")
    boolean balanced,
    @Schema(description = "Every line of the statement has a final status this client can read.")
    boolean statementFinal,
    @Schema(description = "Transfers the client knows the bank accepted in the period, those it learned by asking included.")
    long acceptedByJournal,
    StatementTotals statement,
    ReconciliationBreaks breaks,
    UnknownResolved unknownResolved
) {
}
