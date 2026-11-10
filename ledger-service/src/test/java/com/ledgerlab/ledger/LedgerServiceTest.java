package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.service.LedgerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerServiceTest extends IntegrationTest {

    @Autowired LedgerService ledger;

    @Test
    void posts_a_transfer_as_two_journal_entries_and_moves_the_balance() {
        openBank(2, 1_000);

        var result = applyOne(ledger, transfer(1, 2, 250));

        assertThat(result).isEqualTo(POSTED);
        awaitCreditsApplied();
        assertThat(balances()).containsExactlyInAnyOrderEntriesOf(Map.of(1, 750L, 2, 1_250L));
        assertTheBooksAgree();
        assertThat(reconciliation().postedTransfers()).isEqualTo(1);
        assertThat(check().journalEntries()).isEqualTo(2);
    }

    @Test
    void ignores_a_second_delivery_of_the_same_transfer() {
        openBank(2, 1_000);
        var command = transfer(1, 2, 100);

        assertThat(applyOne(ledger, command)).isEqualTo(POSTED);
        assertThat(applyOne(ledger, command)).isEqualTo(ALREADY_PROCESSED);

        assertThat(balances().get(1)).isEqualTo(900);
        assertThat(reconciliation().postedTransfers()).isEqualTo(1);
    }

    @Test
    void recognises_a_second_delivery_whose_time_of_acceptance_is_finer_than_the_database_keeps() {
        openBank(2, 1_000);
        var command = transferAt(period.start().plusNanos(1_234_567), 1, 2, 100);

        assertThat(applyOne(ledger, command)).isEqualTo(POSTED);
        assertThat(applyOne(ledger, command)).isEqualTo(ALREADY_PROCESSED);

        assertThat(balances().get(1)).isEqualTo(900);
    }

    @Test
    void rejects_insufficient_funds_without_touching_balances() {
        openBank(2, 100);

        var result = applyOne(ledger, transfer(1, 2, 101));

        assertThat(result).isEqualTo(rejected(RejectReason.INSUFFICIENT_FUNDS));
        assertThat(balances().values()).containsOnly(100L);
        assertTheBooksAgree();
        assertThat(reconciliation().rejectionsByReason()).containsEntry(RejectReason.INSUFFICIENT_FUNDS, 1L);
    }

    @Test
    void rejects_transfers_from_or_to_a_blocked_account() {
        // accounts 1..2 active, 3 blocked
        openBank(3, 1_000, 1);

        assertThat(applyOne(ledger, transfer(3, 1, 10))).isEqualTo(rejected(RejectReason.BLOCKED_ACCOUNT));
        assertThat(applyOne(ledger, transfer(1, 3, 10))).isEqualTo(rejected(RejectReason.BLOCKED_ACCOUNT));
        assertThat(applyOne(ledger, transfer(1, 2, 10))).isEqualTo(POSTED);
    }

    @Test
    @DisplayName("rejects non-positive amounts instead of letting the database refuse them")
    void rejects_non_positive_amounts_instead_of_letting_the_database_refuse_them() {
        openBank(2);

        assertThat(applyOne(ledger, transfer(1, 2, 0))).isEqualTo(rejected(RejectReason.INVALID_AMOUNT));
        assertThat(applyOne(ledger, transfer(1, 2, -50))).isEqualTo(rejected(RejectReason.INVALID_AMOUNT));

        assertTheBooksAgree();
        assertThat(reconciliation().rejectionsByReason()).containsEntry(RejectReason.INVALID_AMOUNT, 2L);
    }

    @Test
    @DisplayName("rejects same-account and unknown-account transfers")
    void rejects_same_account_and_unknown_account_transfers() {
        openBank(2);

        assertThat(applyOne(ledger, transfer(1, 1, 1))).isEqualTo(rejected(RejectReason.SAME_ACCOUNT));
        assertThat(applyOne(ledger, transfer(1, 99, 1))).isEqualTo(rejected(RejectReason.UNKNOWN_ACCOUNT));
    }

    @Test
    void to_a_client_the_account_of_the_pool_does_not_exist() {
        openBank(2, 1_000);

        assertThat(applyOne(ledger, transfer(Account.POOL_ACCOUNT_NO, 1, 10))).isEqualTo(rejected(RejectReason.UNKNOWN_ACCOUNT));
        assertThat(applyOne(ledger, transfer(1, Account.POOL_ACCOUNT_NO, 10))).isEqualTo(rejected(RejectReason.UNKNOWN_ACCOUNT));

        assertThat(poolBalance()).isEqualTo(POOL_OPENING_BALANCE_CENTS);
        assertThat(balances().get(1)).isEqualTo(1_000);
    }

    @Test
    void a_batch_of_transfers_posts_each_with_the_outcome_it_would_have_alone() {
        openBank(3, 100);
        var first = transfer(1, 2, 80);
        var batch = List.of(
            first,
            transfer(1, 3, 30),
            first,
            transfer(2, 3, 100),
            transfer(1, 1, 5));

        assertThat(ledger.applyBatch(batch)).containsExactly(
            POSTED,
            rejected(RejectReason.INSUFFICIENT_FUNDS),
            ALREADY_PROCESSED,
            POSTED,
            rejected(RejectReason.SAME_ACCOUNT));

        awaitCreditsApplied();
        assertThat(balances()).containsExactlyInAnyOrderEntriesOf(Map.of(1, 20L, 2, 80L, 3, 200L));
        assertTheBooksAgree();
    }

    @Test
    void an_unknown_account_rejects_its_transfer_and_posts_the_rest_of_the_batch() {
        openBank(2, 1_000);

        var results = ledger.applyBatch(
            List.of(transfer(1, 2, 100), transfer(1, 99, 100), transfer(99, 2, 100), transfer(2, 1, 50)));

        assertThat(results).containsExactly(
            POSTED,
            rejected(RejectReason.UNKNOWN_ACCOUNT),
            rejected(RejectReason.UNKNOWN_ACCOUNT),
            POSTED);
        assertThat(reconciliation().postedTransfers()).isEqualTo(2);
    }

    @Test
    void a_redelivered_batch_posts_nothing_twice() {
        openBank(3, 1_000);
        List<TransferCommand> batch = List.of(transfer(1, 2, 100), transfer(2, 3, 5_000), transfer(3, 1, 10));

        ledger.applyBatch(batch);
        assertThat(ledger.applyBatch(batch)).containsOnly(ALREADY_PROCESSED);

        awaitCreditsApplied();
        var report = reconciliation();
        assertThat(report.postedTransfers()).isEqualTo(2);
        assertThat(report.rejectedTransfers()).isEqualTo(1);
        assertThat(balances()).containsExactlyInAnyOrderEntriesOf(Map.of(1, 910L, 2, 1_100L, 3, 990L));
        assertTheBooksAgree();
    }
}
