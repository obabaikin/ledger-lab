package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.ReconciliationCheck;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.RunService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reconciliation must notice corruption that keeps the totals intact. */
class ReconciliationTest extends IntegrationTest {

    @Autowired LedgerService ledger;
    @Autowired RunService runs;
    @Autowired JdbcClient jdbc;

    private TransferCommand onePostingInANewRun() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(3, 1_000));
        var command = transfer(runId, 1, 2, 100);
        applyOne(ledger, command);
        awaitCreditsApplied(runId);
        assertThat(runs.reconcile(runId).balanced()).isTrue();
        return command;
    }

    @Test
    @DisplayName("sums debits and credits of the journal apart, each as a positive amount, without a rejected transfer")
    void sums_debits_and_credits_of_the_journal_apart_each_as_a_positive_amount_without_a_rejected_transfer() {
        var runId = onePostingInANewRun().runId();
        applyOne(ledger, transfer(runId, 2, 3, 50));
        applyOne(ledger, transfer(runId, 3, 1, 1_000_000));

        var report = runs.reconcile(runId);

        assertThat(report.debitsCents()).isEqualTo(150);
        assertThat(report.creditsCents()).isEqualTo(150);
        assertThat(report.openingTotalCents()).isEqualTo(report.balanceTotalCents());
        assertThat(report.rejectedTransfers()).isEqualTo(1);
    }

    @Test
    void detects_a_credit_booked_to_the_wrong_account_even_when_every_total_still_agrees() {
        var first = onePostingInANewRun();
        var runId = first.runId();
        // Move 100 from account 1 to 3 in the journal without touching balances: totals stay intact.
        jdbc.sql("UPDATE journal_entries SET account_no = 3 WHERE run_id = :runId AND transfer_id = :t AND amount_cents > 0")
            .param("runId", runId).param("t", first.transferId()).update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 100 WHERE run_id = :runId AND account_no = 3")
            .param("runId", runId).update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents - 100 WHERE run_id = :runId AND account_no = 2")
            .param("runId", runId).update();

        var report = runs.reconcile(runId);

        assertThat(report.failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
    }

    @Test
    void detects_a_posting_with_a_third_journal_row_balanced_by_a_missing_row_elsewhere() {
        var first = onePostingInANewRun();
        var runId = first.runId();
        var second = transfer(runId, 2, 3, 50);
        applyOne(ledger, second);
        // Reattach one of the second posting's rows to the first posting: counts and sums per run stay the same.
        jdbc.sql("UPDATE journal_entries SET transfer_id = :first WHERE run_id = :runId AND transfer_id = :second AND amount_cents > 0")
            .param("first", first.transferId()).param("runId", runId).param("second", second.transferId()).update();

        var report = runs.reconcile(runId);

        assertThat(report.failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
    }

    @Test
    void detects_journal_rows_attached_to_a_rejected_transfer() {
        var runId = onePostingInANewRun().runId();
        var rejected = transfer(runId, 1, 2, 1_000_000);
        applyOne(ledger, rejected);
        jdbc.sql("INSERT INTO journal_entries (run_id, transfer_id, account_no, amount_cents) VALUES (:runId, :t, 1, -5), (:runId, :t, 2, 5)")
            .param("runId", runId).param("t", rejected.transferId()).update();

        var report = runs.reconcile(runId);

        assertThat(report.failedChecks()).contains(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
    }

    @Test
    void detects_a_posted_transfer_with_no_journal_rows() {
        var first = onePostingInANewRun();
        var runId = first.runId();
        // Drop both rows and undo their balance effect: only the transfer log still shows the posting.
        jdbc.sql("DELETE FROM journal_entries WHERE run_id = :runId AND transfer_id = :t")
            .param("runId", runId).param("t", first.transferId()).update();
        jdbc.sql("UPDATE accounts SET balance_cents = opening_balance_cents WHERE run_id = :runId")
            .param("runId", runId).update();

        var report = runs.reconcile(runId);

        assertThat(report.failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
    }

    @Test
    void detects_a_posting_whose_debit_amount_differs_from_the_transfer() {
        var first = onePostingInANewRun();
        var runId = first.runId();
        var second = transfer(runId, 2, 3, 50);
        applyOne(ledger, second);
        // Shift 10 between the two postings' debits: each account and every total still agree.
        jdbc.sql("UPDATE journal_entries SET amount_cents = amount_cents - 10 WHERE run_id = :runId AND transfer_id = :t AND amount_cents < 0")
            .param("runId", runId).param("t", first.transferId()).update();
        jdbc.sql("UPDATE journal_entries SET amount_cents = amount_cents + 10 WHERE run_id = :runId AND transfer_id = :t AND amount_cents < 0")
            .param("runId", runId).param("t", second.transferId()).update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents - 10 WHERE run_id = :runId AND account_no = 1")
            .param("runId", runId).update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 10 WHERE run_id = :runId AND account_no = 2")
            .param("runId", runId).update();

        var report = runs.reconcile(runId);

        assertThat(report.failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
    }

    @Test
    void detects_a_drifted_balance_on_an_account_with_no_journal_rows() {
        var runId = onePostingInANewRun().runId();
        // Account 3 took no part in the posting: its check sums zero journal rows.
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 1 WHERE run_id = :runId AND account_no = 3")
            .param("runId", runId).update();

        var report = runs.reconcile(runId);

        assertThat(report.failedChecks()).containsExactlyInAnyOrder(
            ReconciliationCheck.TOTAL_BALANCE_CONSERVED,
            ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL);
    }

    @Test
    void detects_a_balance_that_drifted_from_its_journal() {
        var runId = onePostingInANewRun().runId();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 1 WHERE run_id = :runId AND account_no = 1")
            .param("runId", runId).update();

        var report = runs.reconcile(runId);

        assertThat(report.failedChecks()).containsExactlyInAnyOrder(
            ReconciliationCheck.TOTAL_BALANCE_CONSERVED,
            ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL);
    }
}
