package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.AccountMismatch;
import com.ledgerlab.ledger.domain.ReconciliationCheck;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.PeriodChecker;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The check of a period must notice corruption that keeps the totals intact. */
class PeriodCheckTest extends IntegrationTest {

    @Autowired LedgerService ledger;
    @Autowired PeriodChecker checker;
    @Autowired JdbcClient jdbc;

    private TransferCommand onePostingInANewBank() {
        openBank(3, 1_000);
        var command = transfer(1, 2, 100);
        applyOne(ledger, command);
        awaitCreditsApplied();
        assertTheBooksAgree();
        return command;
    }

    @Test
    @DisplayName("sums debits and credits of the journal apart, each as a positive amount, without a rejected transfer")
    void sums_debits_and_credits_of_the_journal_apart_each_as_a_positive_amount_without_a_rejected_transfer() {
        onePostingInANewBank();
        applyOne(ledger, transfer(2, 3, 50));
        applyOne(ledger, transfer(3, 1, 1_000_000));

        var check = check();

        assertThat(check.debitsCents()).isEqualTo(150);
        assertThat(check.creditsCents()).isEqualTo(150);
        assertThat(check.journalEntries()).isEqualTo(4);
        assertThat(accountRepository.books().balanced()).isTrue();
        assertThat(reconciliation().rejectedTransfers()).isEqualTo(1);
    }

    @Test
    void detects_a_credit_booked_to_the_wrong_account_even_when_every_total_still_agrees() {
        var first = onePostingInANewBank();
        // Move 100 from account 2 to 3 in the journal and in the balances: every account still agrees with its journal.
        jdbc.sql("UPDATE journal_entries SET account_no = 3 WHERE transfer_id = :t AND amount_cents > 0").param("t", first.transferId()).update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 100 WHERE account_no = 3").update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents - 100 WHERE account_no = 2").update();

        var check = check();

        assertThat(check.failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
        assertThat(check.invalidJournalExamples()).containsExactly(first.transferId());
    }

    @Test
    void detects_a_posting_with_a_third_journal_row_balanced_by_a_missing_row_elsewhere() {
        var first = onePostingInANewBank();
        var second = transfer(2, 3, 50);
        applyOne(ledger, second);
        // Reattach one of the second posting's rows to the first posting: counts and sums of the period stay the same.
        jdbc.sql("UPDATE journal_entries SET transfer_id = :first WHERE transfer_id = :second AND amount_cents > 0")
            .param("first", first.transferId()).param("second", second.transferId()).update();

        var check = check();

        assertThat(check.failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
        assertThat(check.transfersWithInvalidJournal()).isEqualTo(2);
    }

    @Test
    void detects_journal_rows_attached_to_a_rejected_transfer() {
        onePostingInANewBank();
        var rejected = transfer(1, 2, 1_000_000);
        applyOne(ledger, rejected);
        jdbc.sql("INSERT INTO journal_entries (accepted_at, transfer_id, account_no, amount_cents) VALUES (:at, :t, 1, -5), (:at, :t, 2, 5)")
            .param("at", Timestamp.from(rejected.acceptedAt())).param("t", rejected.transferId()).update();

        assertThat(check().failedChecks()).contains(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
    }

    @Test
    void detects_journal_rows_of_a_transfer_the_log_does_not_know() {
        var first = onePostingInANewBank();
        // The log entry gone, its journal and the balances left as they are.
        jdbc.sql("DELETE FROM transfers WHERE transfer_id = :t").param("t", first.transferId()).update();

        var check = check();

        assertThat(check.failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
        assertThat(check.invalidJournalExamples()).containsExactly(first.transferId());
    }

    @Test
    void detects_a_posted_transfer_with_no_journal_rows() {
        var first = onePostingInANewBank();
        // Drop both rows and undo their balance effect: only the transfer log still shows the posting.
        jdbc.sql("DELETE FROM journal_entries WHERE transfer_id = :t").param("t", first.transferId()).update();
        jdbc.sql("UPDATE accounts SET balance_cents = opening_balance_cents").update();

        assertThat(check().failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
    }

    @Test
    void detects_a_posting_whose_debit_amount_differs_from_the_transfer() {
        var first = onePostingInANewBank();
        var second = transfer(2, 3, 50);
        applyOne(ledger, second);
        // Shift 10 between the two postings' debits: each account and every total still agree.
        jdbc.sql("UPDATE journal_entries SET amount_cents = amount_cents - 10 WHERE transfer_id = :t AND amount_cents < 0")
            .param("t", first.transferId()).update();
        jdbc.sql("UPDATE journal_entries SET amount_cents = amount_cents + 10 WHERE transfer_id = :t AND amount_cents < 0")
            .param("t", second.transferId()).update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents - 10 WHERE account_no = 1").update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 10 WHERE account_no = 2").update();

        assertThat(check().failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
    }

    @Test
    void detects_a_drifted_balance_on_an_account_with_no_journal_rows() {
        onePostingInANewBank();
        // Account 3 took no part in the posting: its check sums zero journal rows.
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 1 WHERE account_no = 3").update();

        var check = check();

        assertThat(check.failedChecks()).containsExactly(ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL);
        assertThat(check.accountExamples()).containsExactly(new AccountMismatch(3, 0, 1));
        assertThat(accountRepository.books().balanced()).isFalse();
    }

    @Test
    void a_balance_changed_past_the_journal_is_a_mismatch_of_that_account_even_when_the_sum_of_all_balances_is_intact() {
        onePostingInANewBank();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 100 WHERE account_no = 1").update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents - 100 WHERE account_no = 3").update();

        var check = check();

        assertThat(accountRepository.books().balanced()).isTrue();
        assertThat(check.failedChecks()).containsExactly(ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL);
        assertThat(check.accountsOutOfBalance()).isEqualTo(2);
        assertThat(check.accountExamples()).containsExactly(new AccountMismatch(1, -100, 0), new AccountMismatch(3, 0, -100));
    }

    @Test
    void a_transfer_accepted_in_the_last_instant_of_the_period_is_in_it_and_one_accepted_at_its_end_is_not() {
        openBank(3, 1_000);
        applyOne(ledger, transferAt(period.end().minus(1, ChronoUnit.MICROS), 1, 2, 100));
        applyOne(ledger, transferAt(period.end(), 2, 3, 30));
        applyOne(ledger, transferAt(period.end().plusSeconds(1), 3, 1, 1_000_000));
        awaitCreditsApplied();

        var check = check();

        assertThat(check.journalEntries()).isEqualTo(2);
        assertThat(check.debitsCents()).isEqualTo(100);
        assertThat(check.accountsCompared()).isTrue();
        assertThat(check.failedChecks()).isEmpty();
        var counted = reconciliation();
        assertThat(counted.postedTransfers()).isEqualTo(1);
        assertThat(counted.rejectedTransfers()).isZero();
    }

    @Test
    void the_period_after_is_checked_against_the_balances_this_check_kept() {
        openBank(3, 1_000);
        applyOne(ledger, transfer(1, 2, 100));
        applyOne(ledger, transferAt(period.end().plusSeconds(1), 2, 3, 30));
        awaitCreditsApplied();
        check();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 7 WHERE account_no = 3").update();

        var next = check(period.next());

        assertThat(next.accountsCompared()).isTrue();
        assertThat(next.journalEntries()).isEqualTo(2);
        assertThat(next.accountExamples()).containsExactly(new AccountMismatch(3, 30, 37));
    }

    @Test
    void without_the_balances_of_the_period_before_the_accounts_are_not_compared_and_nothing_is_reported_as_wrong() {
        onePostingInANewBank();
        jdbc.sql("DELETE FROM account_snapshots").update();
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 1 WHERE account_no = 3").update();

        var check = check();

        assertThat(check.accountsCompared()).isFalse();
        assertThat(check.accountsOutOfBalance()).isZero();
        assertThat(check.failedChecks()).isEmpty();
    }

    @Test
    void a_period_is_checked_once() {
        onePostingInANewBank();
        check();

        assertThat(checker.check(period)).isFalse();

        assertThat(jdbc.sql("SELECT count(*) FROM period_checks").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reports_how_many_accounts_are_out_of_balance_and_names_no_more_than_twenty() {
        openBank(30, 1_000);
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 1 WHERE account_no BETWEEN 1 AND 25").update();

        var check = check();

        assertThat(check.accountsOutOfBalance()).isEqualTo(25);
        assertThat(check.accountExamples()).extracting(AccountMismatch::accountNo).hasSize(20).startsWith(1, 2, 3).endsWith(20);
    }

    @Test
    void balances_and_checks_kept_longer_than_their_retention_are_deleted_by_the_next_check() {
        onePostingInANewBank();
        var expired = period.start().minus(periodsProperties.snapshotRetention()).minus(Duration.ofHours(1));
        var held = period.start().minus(periodsProperties.snapshotRetention()).plus(Duration.ofHours(1));
        for (var start : new Instant[] {expired, held}) {
            jdbc.sql("INSERT INTO account_snapshots (period_start, account_no, balance_cents) VALUES (:start, 1, 0)")
                .param("start", Timestamp.from(start)).update();
            jdbc.sql("INSERT INTO period_checks (period_start, report) VALUES (:start, '{}')").param("start", Timestamp.from(start)).update();
        }

        check();

        assertThat(jdbc.sql("SELECT period_start FROM account_snapshots WHERE account_no = 1 AND balance_cents = 0").query(Timestamp.class).list())
            .extracting(Timestamp::toInstant).containsExactly(held);
        assertThat(jdbc.sql("SELECT period_start FROM period_checks WHERE report = '{}'").query(Timestamp.class).list())
            .extracting(Timestamp::toInstant).containsExactly(held);
    }
}
