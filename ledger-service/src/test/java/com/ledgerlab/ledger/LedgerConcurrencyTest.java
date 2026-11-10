package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.AccountKey;
import com.ledgerlab.ledger.domain.TransferResult;
import com.ledgerlab.ledger.persistence.AccountRepository;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.RunService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerConcurrencyTest extends IntegrationTest {

    private static final int LOCKED_ACCOUNTS = 2_000;
    private static final int LOCK_ROUNDS = 40;
    private static final int ROUNDS = 10;

    @Autowired LedgerService ledger;
    @Autowired RunService runs;
    @Autowired AccountRepository accounts;
    @Autowired TransactionTemplate tx;

    @Test
    void concurrent_transfers_between_a_few_hot_accounts_never_deadlock_and_always_reconcile() throws Exception {
        var runId = UUID.randomUUID();
        var accountCount = 5;
        runs.openAccounts(runId, setup(accountCount, 10_000));
        var random = new Random(42);
        var commands = new ArrayList<TransferCommand>();
        for (var i = 0; i < 1_000; i++) {
            var from = random.nextInt(1, accountCount + 1);
            var to = random.ints(1, accountCount + 1).filter(candidate -> candidate != from).findFirst().orElseThrow();
            commands.add(transfer(runId, from, to, random.nextLong(1, 500)));
        }

        var results = inParallel(16, commands.stream().<Callable<TransferResult>>map(command -> () -> applyOne(ledger, command)).toList());

        assertThat(results).allMatch(it -> it instanceof TransferResult.Posted || it instanceof TransferResult.Rejected);
        var report = runs.reconcile(runId);
        assertThat(report.failedChecks()).isEmpty();
        assertThat(report.postedTransfers() + report.rejectedTransfers()).isEqualTo(commands.size());
        assertThat(report.balanceTotalCents()).isEqualTo(accountCount * 10_000L);
    }

    @Test
    void concurrent_deliveries_of_the_same_transfer_post_it_exactly_once() throws Exception {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(2, 1_000));
        var command = transfer(runId, 1, 2, 100);

        var results = inParallel(16, Collections.nCopies(16, () -> applyOne(ledger, command)));

        assertThat(results).filteredOn(POSTED::equals).hasSize(1);
        assertThat(results).filteredOn(ALREADY_PROCESSED::equals).hasSize(15);
        assertThat(balances(runs, runId).get(1)).isEqualTo(900);
        assertThat(runs.reconcile(runId).failedChecks()).isEmpty();
    }

    @Test
    void transactions_locking_overlapping_accounts_given_in_different_orders_all_commit_without_a_deadlock() throws Exception {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(LOCKED_ACCOUNTS));
        var pool = Executors.newFixedThreadPool(3);
        try {
            for (var round = 0; round < LOCK_ROUNDS; round++) {
                var shuffled = new ArrayList<>(keys(runId, LOCKED_ACCOUNTS / 3));
                Collections.shuffle(shuffled);
                var start = new CountDownLatch(1);
                var locking = new ArrayList<Future<Integer>>();
                for (var order : List.of(keys(runId, LOCKED_ACCOUNTS), keys(runId, LOCKED_ACCOUNTS / 7).reversed(), shuffled)) {
                    locking.add(pool.submit(() -> {
                        start.await();
                        return tx.execute(status -> lockAndRewrite(order));
                    }));
                }
                start.countDown();
                var locked = new ArrayList<Integer>();
                for (var future : locking) {
                    locked.add(future.get(30, TimeUnit.SECONDS));
                }

                assertThat(locked).containsExactly(LOCKED_ACCOUNTS, LOCKED_ACCOUNTS / 7, LOCKED_ACCOUNTS / 3);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<AccountKey> keys(UUID runId, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(accountNo -> new AccountKey(runId, accountNo)).toList();
    }

    /** Rewriting the rows moves them in the table, as the balance updates of real batches do between two locks. */
    private int lockAndRewrite(List<AccountKey> order) {
        var locked = accounts.lockAll(new LinkedHashSet<>(order));
        accounts.updateBalances(locked);
        return locked.size();
    }

    @Test
    void concurrent_deliveries_of_a_transfer_that_locks_no_account_report_its_rejection_once() throws Exception {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(2));
        var pool = Executors.newFixedThreadPool(16);
        try {
            for (var round = 0; round < ROUNDS; round++) {
                var command = transfer(runId, 1, 2, 0);

                var results = inParallel(pool, Collections.nCopies(16, () -> applyOne(ledger, command)));

                assertThat(results).filteredOn(rejected(RejectReason.INVALID_AMOUNT)::equals).hasSize(1);
                assertThat(results).filteredOn(ALREADY_PROCESSED::equals).hasSize(15);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> List<T> inParallel(int threads, List<Callable<T>> tasks) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        try {
            return inParallel(pool, tasks);
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> List<T> inParallel(ExecutorService pool, List<Callable<T>> tasks) throws Exception {
        var results = new ArrayList<T>();
        for (var future : pool.invokeAll(tasks)) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }
}
