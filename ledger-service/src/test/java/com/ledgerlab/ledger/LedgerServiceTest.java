package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.RunService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerServiceTest extends IntegrationTest {

    @Autowired LedgerService ledger;
    @Autowired RunService runs;

    @Test
    void posts_a_transfer_as_two_journal_entries_and_moves_the_balance() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(2, 1_000));

        var result = applyOne(ledger, transfer(runId, 1, 2, 250));

        assertThat(result).isEqualTo(POSTED);
        awaitCreditsApplied(runId);
        assertThat(balances(runs, runId)).containsExactlyInAnyOrderEntriesOf(Map.of(1, 750L, 2, 1_250L));
        var report = runs.reconcile(runId);
        assertThat(report.balanced()).isTrue();
        assertThat(report.postedTransfers()).isEqualTo(1);
        assertThat(report.journalEntries()).isEqualTo(2);
    }

    @Test
    void ignores_a_second_delivery_of_the_same_transfer() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(2, 1_000));
        var command = transfer(runId, 1, 2, 100);

        assertThat(applyOne(ledger, command)).isEqualTo(POSTED);
        assertThat(applyOne(ledger, command)).isEqualTo(ALREADY_PROCESSED);

        assertThat(balances(runs, runId).get(1)).isEqualTo(900);
        assertThat(runs.reconcile(runId).postedTransfers()).isEqualTo(1);
    }

    @Test
    void rejects_insufficient_funds_without_touching_balances() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(2, 100));

        var result = applyOne(ledger, transfer(runId, 1, 2, 101));

        assertThat(result).isEqualTo(rejected(RejectReason.INSUFFICIENT_FUNDS));
        assertThat(runs.listAccounts(runId)).extracting(Account::balanceCents).containsOnly(100L);
        var report = runs.reconcile(runId);
        assertThat(report.balanced()).isTrue();
        assertThat(report.rejectionsByReason()).containsEntry(RejectReason.INSUFFICIENT_FUNDS, 1L);
    }

    @Test
    void rejects_transfers_from_or_to_a_blocked_account() {
        var runId = UUID.randomUUID();
        // accounts 1..2 active, 3 blocked
        runs.openAccounts(runId, setup(3, 1_000, 1));

        assertThat(applyOne(ledger, transfer(runId, 3, 1, 10))).isEqualTo(rejected(RejectReason.BLOCKED_ACCOUNT));
        assertThat(applyOne(ledger, transfer(runId, 1, 3, 10))).isEqualTo(rejected(RejectReason.BLOCKED_ACCOUNT));
        assertThat(applyOne(ledger, transfer(runId, 1, 2, 10))).isEqualTo(POSTED);
    }

    @Test
    void rejects_when_the_daily_limit_would_be_exceeded() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setupWithDailyLimit(2, 10_000, 300));

        assertThat(applyOne(ledger, transfer(runId, 1, 2, 200))).isEqualTo(POSTED);
        assertThat(applyOne(ledger, transfer(runId, 1, 2, 101))).isEqualTo(rejected(RejectReason.DAILY_LIMIT_EXCEEDED));
        assertThat(applyOne(ledger, transfer(runId, 1, 2, 100))).isEqualTo(POSTED);
    }

    @Test
    @DisplayName("rejects non-positive amounts instead of letting the database refuse them")
    void rejects_non_positive_amounts_instead_of_letting_the_database_refuse_them() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(2));

        assertThat(applyOne(ledger, transfer(runId, 1, 2, 0))).isEqualTo(rejected(RejectReason.INVALID_AMOUNT));
        assertThat(applyOne(ledger, transfer(runId, 1, 2, -50))).isEqualTo(rejected(RejectReason.INVALID_AMOUNT));

        var report = runs.reconcile(runId);
        assertThat(report.balanced()).isTrue();
        assertThat(report.rejectionsByReason()).containsEntry(RejectReason.INVALID_AMOUNT, 2L);
    }

    @Test
    @DisplayName("rejects same-account and unknown-account transfers")
    void rejects_same_account_and_unknown_account_transfers() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(2));

        assertThat(applyOne(ledger, transfer(runId, 1, 1, 1))).isEqualTo(rejected(RejectReason.SAME_ACCOUNT));
        assertThat(applyOne(ledger, transfer(runId, 1, 99, 1))).isEqualTo(rejected(RejectReason.UNKNOWN_ACCOUNT));
    }

    @Test
    void a_batch_of_transfers_posts_each_with_the_outcome_it_would_have_alone() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(3, 100));
        var first = transfer(runId, 1, 2, 80);
        var batch = List.of(
            first,
            transfer(runId, 1, 3, 30),
            first,
            transfer(runId, 2, 3, 100),
            transfer(runId, 1, 1, 5));

        assertThat(ledger.applyBatch(batch)).containsExactly(
            POSTED,
            rejected(RejectReason.INSUFFICIENT_FUNDS),
            ALREADY_PROCESSED,
            POSTED,
            rejected(RejectReason.SAME_ACCOUNT));

        awaitCreditsApplied(runId);
        assertThat(balances(runs, runId)).containsExactlyInAnyOrderEntriesOf(Map.of(1, 20L, 2, 80L, 3, 200L));
        assertThat(runs.reconcile(runId).failedChecks()).isEmpty();
    }

    @Test
    void an_unknown_account_rejects_its_transfer_and_posts_the_rest_of_the_batch() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(2, 1_000));

        var results = ledger.applyBatch(
            List.of(transfer(runId, 1, 2, 100), transfer(runId, 1, 99, 100), transfer(runId, 99, 2, 100), transfer(runId, 2, 1, 50)));

        assertThat(results).containsExactly(
            POSTED,
            rejected(RejectReason.UNKNOWN_ACCOUNT),
            rejected(RejectReason.UNKNOWN_ACCOUNT),
            POSTED);
        assertThat(runs.reconcile(runId).postedTransfers()).isEqualTo(2);
    }

    @Test
    void a_redelivered_batch_posts_nothing_twice() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(3, 1_000));
        var batch = List.of(transfer(runId, 1, 2, 100), transfer(runId, 2, 3, 5_000), transfer(runId, 3, 1, 10));

        ledger.applyBatch(batch);

        assertThat(ledger.applyBatch(batch)).containsOnly(ALREADY_PROCESSED);
        awaitCreditsApplied(runId);
        var report = runs.reconcile(runId);
        assertThat(report.postedTransfers()).isEqualTo(2);
        assertThat(report.rejectedTransfers()).isEqualTo(1);
        assertThat(balances(runs, runId)).containsExactlyInAnyOrderEntriesOf(Map.of(1, 910L, 2, 1_100L, 3, 990L));
        assertThat(report.failedChecks()).isEmpty();
    }

    @Test
    void runs_are_isolated_from_each_other() {
        var runA = UUID.randomUUID();
        var runB = UUID.randomUUID();
        runs.openAccounts(runA, setup(2, 1_000));
        runs.openAccounts(runB, setup(2, 1_000));

        applyOne(ledger, transfer(runA, 1, 2, 400));

        assertThat(runs.listAccounts(runB)).extracting(Account::balanceCents).containsOnly(1_000L);
        assertThat(runs.reconcile(runA).postedTransfers()).isEqualTo(1);
        assertThat(runs.reconcile(runB).postedTransfers()).isEqualTo(0);
    }
}
