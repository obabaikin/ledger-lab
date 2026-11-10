package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.service.DeferredCreditBatch;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.RunService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

// The schedule would apply credits between the steps of a test; here the tests apply them themselves.
@TestPropertySource(properties = "ledger.deferred-credits.apply-interval=1h")
class DeferredCreditTest extends IntegrationTest {

    private static final int DEADLOCK_ROUNDS = 20;

    @Autowired LedgerService ledger;
    @Autowired RunService runs;
    @Autowired DeferredCreditBatch credits;
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;

    @Test
    void a_pending_credit_counts_in_reconciliation_and_reaches_the_balance_once_applied() {
        var runId = openRun(3, 1_000);

        assertThat(ledger.applyBatch(List.of(transfer(runId, 2, 1, 100), transfer(runId, 2, 3, 50)))).containsOnly(POSTED);

        assertThat(balances(runs, runId)).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1_000L, 2, 850L, 3, 1_000L));
        assertThat(runs.reconcile(runId).failedChecks()).isEmpty();
        assertThat(runs.reconcile(runId).balanceTotalCents()).isEqualTo(3_000);

        applyAll();
        applyAll();

        assertThat(balances(runs, runId)).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1_100L, 2, 850L, 3, 1_050L));
        assertThat(pendingCredits(runId)).isZero();
        assertThat(runs.reconcile(runId).failedChecks()).isEmpty();
    }

    @Test
    void a_receiver_that_also_sends_in_the_batch_gets_its_credit_once() {
        var runId = openRun(3, 1_000);

        var results = ledger.applyBatch(List.of(transfer(runId, 2, 1, 300), transfer(runId, 1, 3, 200), transfer(runId, 3, 1, 100)));

        assertThat(results).containsOnly(POSTED);
        assertThat(balances(runs, runId).get(1)).isEqualTo(800);
        applyAll();
        assertThat(balances(runs, runId)).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1_200L, 2, 700L, 3, 1_100L));
        assertThat(runs.reconcile(runId).failedChecks()).isEmpty();
    }

    @Test
    void a_sender_cannot_spend_a_credit_that_is_not_applied_yet() {
        var runId = openRun(3, 1_000);

        applyOne(ledger, transfer(runId, 2, 1, 500));

        assertThat(applyOne(ledger, transfer(runId, 1, 3, 1_200))).isEqualTo(rejected(RejectReason.INSUFFICIENT_FUNDS));
        applyAll();
        assertThat(applyOne(ledger, transfer(runId, 1, 3, 1_200))).isEqualTo(POSTED);
    }

    @Test
    void two_appliers_at_once_add_each_credit_once() throws Exception {
        var runId = openRun(6, 100_000);
        for (var i = 0; i < 200; i++) {
            applyOne(ledger, transfer(runId, 3 + i % 4, 1 + i % 2, 10));
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

        var balances = balances(runs, runId);
        assertThat(Map.of(1, balances.get(1), 2, balances.get(2))).containsExactlyInAnyOrderEntriesOf(Map.of(1, 101_000L, 2, 101_000L));
        assertThat(pendingCredits(runId)).isZero();
        assertThat(runs.reconcile(runId).failedChecks()).isEmpty();
    }

    @Test
    void the_applier_and_a_batch_taking_the_same_accounts_wait_for_each_other_instead_of_deadlocking() throws Exception {
        var accounts = 200;
        var runId = openRun(accounts + 1, 100_000);
        var payer = accounts + 1;
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (var round = 0; round < DEADLOCK_ROUNDS; round++) {
                // One by one: the applier takes credits in the order they were recorded, here against the order of the lock.
                for (var to = accounts; to >= 1; to--) {
                    applyOne(ledger, transfer(runId, payer, to, 1));
                }
                var debited = new ArrayList<TransferCommand>();
                for (var from = 1; from <= accounts; from++) {
                    debited.add(transfer(runId, from, payer, 1));
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
        assertThat(pendingCredits(runId)).isZero();
        assertThat(runs.reconcile(runId).failedChecks()).isEmpty();
        // Thousands of results would keep the publisher and the readers of the results topic busy for the tests after this one.
        runs.deleteRun(runId);
    }

    @Test
    void deleting_a_run_deletes_its_pending_credits() {
        var runId = openRun(3, 1_000);
        applyOne(ledger, transfer(runId, 2, 1, 100));

        runs.deleteRun(runId);

        assertThat(pendingCredits(runId)).isZero();
    }

    @Test
    void deleting_a_run_while_the_applier_holds_its_credits_waits_for_the_applier_instead_of_deadlocking() throws Exception {
        var runId = openRun(3, 1_000);
        applyOne(ledger, transfer(runId, 2, 1, 100));
        try (var applier = dataSource.getConnection()) {
            applier.setAutoCommit(false);
            try (var delete = applier.prepareStatement("DELETE FROM deferred_credits WHERE run_id = ?")) {
                delete.setObject(1, runId);
                delete.executeUpdate();
            }
            var waitingBefore = waitingLocks();

            var deleting = CompletableFuture.supplyAsync(() -> runs.deleteRun(runId));
            await().atMost(Duration.ofSeconds(5)).until(() -> waitingLocks() > waitingBefore);
            try (var lock = applier.prepareStatement("SELECT 1 FROM accounts WHERE run_id = ? AND account_no = 1 FOR NO KEY UPDATE")) {
                lock.setObject(1, runId);
                lock.executeQuery().close();
            }
            applier.commit();

            assertThat(deleting.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(runs.listAccounts(runId)).isEmpty();
    }

    private int waitingLocks() {
        return jdbc.sql("SELECT count(*) FROM pg_locks WHERE NOT granted").query(Integer.class).single();
    }

    private UUID openRun(int accounts, long openingBalanceCents) {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(accounts, openingBalanceCents));
        return runId;
    }

    private void applyAll() {
        while (credits.applyNext() > 0) {
            Thread.onSpinWait();
        }
    }
}
