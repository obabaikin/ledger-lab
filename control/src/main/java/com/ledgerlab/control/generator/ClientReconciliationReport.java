package com.ledgerlab.control.generator;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;

@Schema(description = "The clients' journal against the period's statement, as the generator reported it.")
public record ClientReconciliationReport(
    Instant periodStart,
    boolean balanced,
    @Schema(description = "No statement line was still in flight.")
    boolean statementFinal,
    long acceptedByJournal,
    StatementTotals statement,
    ReconciliationBreaks breaks,
    UnknownResolved unknownResolved
) {

    public ClientReconciliationReport {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(breaks, "breaks");
        Objects.requireNonNull(unknownResolved, "unknownResolved");
    }
}
