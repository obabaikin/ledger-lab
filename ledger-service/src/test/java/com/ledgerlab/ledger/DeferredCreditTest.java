package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.service.DeferredCreditBatch;
import com.ledgerlab.ledger.service.LedgerService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

// The schedule would apply credits between the steps of a test; here the tests apply them themselves.
@TestPropertySource(properties = "ledger.deferred-credits.apply-interval=1h")
class DeferredCreditTest extends IntegrationTest {

    private static final int DEADLOCK_ROUNDS = 20;

    @Autowired LedgerService ledger;
    @Autowired DeferredCreditBatch credits;

    @Test
    void a_pending_credit_counts_in_reconciliation_and_reaches_the_balance_once_applied() {
        openBank(3, 1_000);

        assertThat(ledger.applyBatch(List.of(transfer(2, 1, 100), transfer(2, 3, 50)))).containsOnly(POSTED);

        assertThat(balances()).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1_000L, 2, 850L, 3, 1_000L));
        assertTheBooksAgree();
        assertThat(accountRepository.books().balanceTotalCents()).isEqualTo(3_000 + POOL_OPENING_BALANCE_CENTS);

        applyAll();
        applyAll();

        assertThat(balances()).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1_100L, 2, 850L, 3, 1_050L));
        assertThat(pendingCredits()).isZero();
        assertTheBooksAgree();
    }

    @Test
    void a_receiver_that_also_sends_in_the_batch_gets_its_credit_once() {
        openBank(3, 1_000);

        var results = ledger.applyBatch(List.of(transfer(2, 1, 300), transfer(1, 3, 200), transfer(3, 1, 100)));

        assertThat(results).containsOnly(POSTED);
        assertThat(balances().get(1)).isEqualTo(800);
        applyAll();
        assertThat(balances()).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1_200L, 2, 700L, 3, 1_100L));
        assertTheBooksAgree();
    }

    @Test
    void a_sender_cannot_spend_a_credit_that_is_not_applied_yet() {
        openBank(3, 1_000);

        applyOne(ledger, transfer(2, 1, 500));

        assertThat(applyOne(ledger, transfer(1, 3, 1_200))).isEqualTo(rejected(RejectReason.INSUFFICIENT_FUNDS));
        applyAll();
        assertThat(applyOne(ledger, transfer(1, 3, 1_200))).isEqualTo(POSTED);
    }

    @Test
    void two_appliers_at_once_add_each_credit_once() throws Exception {
        openBank(6, 100_000);
        for (var i = 0; i < 200; i++) {
            applyOne(ledger, transfer(3 + i % 4, 1 + i % 2, 10));
        }

        var pool = Executors.newFixedThreadPool(8);
        try {
            Callable<Void> apply = () -> {
                applyAll();
                return null;
            };
            for (var future : pool.invokeAll(Collections.nCopies(8, apply))) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        var balances = balances();
        assertThat(Map.of(1, balances.get(1), 2, balances.get(2))).containsExactlyInAnyOrderEntriesOf(Map.of(1, 101_000L, 2, 101_000L));
        assertThat(pendingCredits()).isZero();
        assertTheBooksAgree();
    }

    @Test
    void the_applier_and_a_batch_taking_the_same_accounts_wait_for_each_other_instead_of_deadlocking() throws Exception {
        var accounts = 200;
        openBank(accounts + 1, 100_000);
        var payer = accounts + 1;
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (var round = 0; round < DEADLOCK_ROUNDS; round++) {
                // One by one: the applier takes credits in the order they were recorded, here against the order of the lock.
                for (var to = accounts; to >= 1; to--) {
                    applyOne(ledger, transfer(payer, to, 1));
                }
                var debited = new ArrayList<TransferCommand>();
                for (var from = 1; from <= accounts; from++) {
                    debited.add(transfer(from, payer, 1));
                }
                var start = new CountDownLatch(1);
                var applying = pool.submit(() -> {
                    start.await();
                    applyAll();
                    return null;
                });
                var posting = pool.submit(() -> {
                    start.await();
                    return ledger.applyBatch(debited);
                });
                start.countDown();

                applying.get(30, TimeUnit.SECONDS);
                assertThat(posting.get(30, TimeUnit.SECONDS)).containsOnly(POSTED);
            }
        } finally {
            pool.shutdownNow();
        }
        applyAll();
        assertThat(pendingCredits()).isZero();
        assertTheBooksAgree();
    }

    @Test
    void a_credit_not_yet_applied_when_the_period_is_checked_is_no_mismatch() {
        openBank(3, 1_000);
        assertThat(applyOne(ledger, transfer(2, 1, 100))).isEqualTo(POSTED);
        assertThat(pendingCredits()).isEqualTo(1);

        var check = check();

        assertThat(check.accountsCompared()).isTrue();
        assertThat(check.accountsOutOfBalance()).isZero();
        assertThat(check.failedChecks()).isEmpty();
    }

    private void applyAll() {
        while (credits.applyNext() > 0) {
            Thread.onSpinWait();
        }
    }
}
