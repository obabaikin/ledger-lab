package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.reporting.persistence.AcceptedTransfer;
import com.ledgerlab.reporting.persistence.RunStatsRepository;
import com.ledgerlab.reporting.persistence.TransferOutcomeRow;
import com.ledgerlab.reporting.persistence.TransferStatsRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class ConcurrentWritersTest extends IntegrationTest {

    private static final int ROWS = 400;
    private static final int ROUNDS = 10;

    @Autowired TransferStatsRepository transfers;
    @Autowired RunStatsRepository stats;
    @Autowired TransactionTemplate tx;

    @Test
    @DisplayName("commands and results of the same transfers written at once in opposite orders neither deadlock nor overwrite each other")
    void commands_and_results_written_at_once_in_opposite_orders_neither_deadlock_nor_overwrite_each_other() throws Exception {
        var runId = UUID.randomUUID();
        var now = Instant.now();
        var ids = Stream.generate(UUID::randomUUID).limit(ROWS).toList();
        var accepted = ids.stream().map(id -> new AcceptedTransfer(id, runId, 10, now)).toList();
        var outcomes = ids.reversed().stream()
            .map(id -> new TransferOutcomeRow(id, runId, TransferOutcome.POSTED.name(), null, now))
            .toList();
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);

        try {
            for (var round = 0; round < ROUNDS; round++) {
                var writes = List.of(
                    pool.submit(afterBarrier(barrier, () -> tx.executeWithoutResult(status -> transfers.upsertAccepted(accepted)))),
                    pool.submit(afterBarrier(barrier, () -> tx.executeWithoutResult(status -> transfers.upsertOutcomes(outcomes)))));
                for (var write : writes) {
                    write.get(30, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        var totals = stats.snapshot(runId).totals();
        assertThat(totals.accepted()).isEqualTo(ROWS);
        assertThat(totals.posted()).isEqualTo(ROWS);
        assertThat(totals.postedAmountCents()).isEqualTo(10L * ROWS);
    }

    private static Callable<Void> afterBarrier(CyclicBarrier barrier, Runnable write) {
        return () -> {
            barrier.await();
            write.run();
            return null;
        };
    }
}
