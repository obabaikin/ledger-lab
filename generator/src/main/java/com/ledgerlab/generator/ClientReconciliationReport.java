package com.ledgerlab.generator;

import java.util.UUID;

public record ClientReconciliationReport(
    UUID runId,
    boolean balanced,
    boolean statementFinal,
    JournalTotals journal,
    StatementTotals statement,
    ReconciliationBreaks breaks,
    UnknownResolved unknownResolved
) {
}
