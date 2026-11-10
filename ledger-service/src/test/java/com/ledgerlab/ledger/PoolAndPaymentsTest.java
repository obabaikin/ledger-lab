package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.pool.PoolProperties;
import com.ledgerlab.ledger.pool.PoolService;
import com.ledgerlab.ledger.service.LedgerService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"ledger.accounts.count=200", "ledger.pool.max-batch=200"})
class PoolAndPaymentsTest extends IntegrationTest {

    private static final int ACCOUNTS = 200;
    private static final int ROUNDS = 20;

    @Autowired PoolService pool;
    @Autowired PoolProperties props;
    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;

    @Test
    void the_pool_and_a_batch_taking_the_same_accounts_wait_for_each_other_instead_of_deadlocking() throws Exception {
        openBank(ACCOUNTS, 100_000);
        var payer = ACCOUNTS;
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (var round = 0; round < ROUNDS; round++) {
                // Every account above its opening balance, so the pool has a posting for each of them.
                var credited = new ArrayList<TransferCommand>();
                for (var to = 1; to < payer; to++) {
                    credited.add(transfer(payer, to, 1));
                }
                assertThat(ledger.applyBatch(credited)).containsOnly(POSTED);
                awaitCreditsApplied();
                var debited = new ArrayList<TransferCommand>();
                for (var from = 1; from < payer; from++) {
                    debited.add(transfer(from, payer, 1));
                }
                // From the middle: the pool meets the accounts in another order than their numbers.
                var now = aWholeRoundIsDueFrom(ACCOUNTS / 2);

                var start = new CountDownLatch(1);
                var pooling = executor.submit(() -> {
                    start.await();
                    return pool.advance(now);
                });
                var posting = executor.submit(() -> {
                    start.await();
                    return ledger.applyBatch(debited);
                });
                start.countDown();

                assertThat(pooling.get(30, TimeUnit.SECONDS)).isEqualTo(ACCOUNTS);
                assertThat(posting.get(30, TimeUnit.SECONDS)).containsOnly(POSTED);
            }
        } finally {
            executor.shutdownNow();
        }
        awaitCreditsApplied();
        assertThat(accountRepository.books().balanced()).isTrue();
        assertThat(check().failedChecks()).isEmpty();
    }

    private Instant aWholeRoundIsDueFrom(int nextAccountNo) {
        var now = period.start().plusSeconds(60);
        jdbc.sql("UPDATE pool_state SET next_account_no = :next, round_advanced_at = :round, hot_swept_at = :sweep")
            .param("next", nextAccountNo)
            .param("round", Timestamp.from(now.minus(props.round())))
            .param("sweep", Timestamp.from(now))
            .update();
        return now;
    }
}
