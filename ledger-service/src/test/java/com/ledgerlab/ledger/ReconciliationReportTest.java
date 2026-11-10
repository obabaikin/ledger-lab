package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.ledger.domain.CheckResult;
import com.ledgerlab.ledger.domain.ReconciliationCheck;
import com.ledgerlab.ledger.domain.ReconciliationReport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationReportTest {

    private static final long TOTAL = 1_000;

    private static ReconciliationReport report(long balanceTotal, long journalSum, long invalidJournal, long outOfBalance) {
        return new ReconciliationReport(
            UUID.randomUUID(), 2, TOTAL, balanceTotal, 3, 0, Map.of(), 6, journalSum, 0, 0, invalidJournal, outOfBalance);
    }

    @Test
    void is_balanced_when_every_check_passes() {
        var report = report(TOTAL, 0, 0, 0);

        assertThat(report.balanced()).isTrue();
        assertThat(report.failedChecks()).isEmpty();
        assertThat(report.checks()).extracting(CheckResult::check).containsExactly(ReconciliationCheck.values());
    }

    @Test
    void names_the_check_that_failed() {
        assertThat(report(999, 0, 0, 0).failedChecks()).containsExactly(ReconciliationCheck.TOTAL_BALANCE_CONSERVED);
        assertThat(report(TOTAL, 5, 0, 0).failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_SUMS_TO_ZERO);
        assertThat(report(TOTAL, 0, 1, 0).failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
        assertThat(report(TOTAL, 0, 0, 1).failedChecks()).containsExactly(ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL);
    }
}
