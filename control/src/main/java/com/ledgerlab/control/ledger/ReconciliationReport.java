package com.ledgerlab.control.ledger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ReconciliationReport(
    UUID runId,
    boolean balanced,
    List<ReconciliationCheck> failedChecks,
    long postedTransfers,
    long rejectedTransfers,
    Map<String, Long> rejectionsByReason,
    long openingTotalCents,
    long balanceTotalCents,
    long debitsCents,
    long creditsCents
) {
    public ReconciliationReport {
        Objects.requireNonNull(runId, "runId");
        failedChecks = List.copyOf(Objects.requireNonNull(failedChecks, "failedChecks"));
        Objects.requireNonNull(rejectionsByReason, "rejectionsByReason");
    }
}
