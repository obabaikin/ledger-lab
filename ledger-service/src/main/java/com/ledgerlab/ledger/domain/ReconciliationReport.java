package com.ledgerlab.ledger.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ledgerlab.common.RejectReason;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Independent cross-check of a run's ledger state. All three views must agree:
 * account balances, the double-entry journal, and the transfer log.
 */
public record ReconciliationReport(
    UUID runId,
    long accounts,
    long openingTotalCents,
    long balanceTotalCents,
    long postedTransfers,
    long rejectedTransfers,
    Map<RejectReason, Long> rejectionsByReason,
    long journalEntries,
    long journalSumCents,
    long debitsCents,
    long creditsCents,
    long transfersWithInvalidJournal,
    long accountsOutOfBalance
) {
    @JsonProperty
    public List<CheckResult> checks() {
        return List.of(
            new CheckResult(ReconciliationCheck.TOTAL_BALANCE_CONSERVED, balanceTotalCents == openingTotalCents),
            new CheckResult(ReconciliationCheck.JOURNAL_SUMS_TO_ZERO, journalSumCents == 0L),
            new CheckResult(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG, transfersWithInvalidJournal == 0L),
            new CheckResult(ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL, accountsOutOfBalance == 0L));
    }

    @JsonProperty
    public List<ReconciliationCheck> failedChecks() {
        return checks().stream().filter(result -> !result.passed()).map(CheckResult::check).toList();
    }

    @JsonProperty
    public boolean balanced() {
        return failedChecks().isEmpty();
    }
}
