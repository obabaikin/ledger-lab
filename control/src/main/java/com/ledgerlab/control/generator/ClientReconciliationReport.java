package com.ledgerlab.control.generator;

import java.util.Objects;
import java.util.UUID;

public record ClientReconciliationReport(
    UUID runId,
    boolean balanced,
    boolean statementFinal,
    JournalTotals journal,
    ReconciliationBreaks breaks,
    UnknownResolved unknownResolved
) {
    public ClientReconciliationReport {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(breaks, "breaks");
        Objects.requireNonNull(unknownResolved, "unknownResolved");
    }
}
