package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.common.TransferResultEvent;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConcurrentWritersTest extends IntegrationTest {

    private static final int ROWS = 400;
    private static final int ROUNDS = 10;
    private static final long AMOUNT_CENTS = 10;

    @Test
    @DisplayName("commands and results of the same transfers written at once in opposite orders neither deadlock nor count a transfer twice")
    void commands_and_results_written_at_once_in_opposite_orders_neither_deadlock_nor_count_a_transfer_twice() throws Exception {
        var ended = lastEndedPeriod();
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);

        try {
            for (var round = 0; round < ROUNDS; round++) {
                // New transfers each round, so both writers race to create every row; spread over a few seconds.
                var commands = IntStream.range(0, ROWS)
                    .mapToObj(i -> command(AMOUNT_CENTS, ended.start().plusMillis(i * 10L)))
                    .toArray(TransferCommand[]::new);
                var results = IntStream.range(0, ROWS)
                    .mapToObj(i -> posted(commands[ROWS - 1 - i], ended.end()))
                    .toArray(TransferResultEvent[]::new);
                var writes = List.of(
                    pool.submit(afterBarrier(barrier, () -> store(commands))),
                    pool.submit(afterBarrier(barrier, () -> store(results))));
                for (var write : writes) {
                    write.get(30, TimeUnit.SECONDS);
                }
                // Redelivered after both committed.
                store(commands);
                store(results);
            }
        } finally {
            pool.shutdownNow();
        }

        var stats = seconds.sum(ended);
        assertThat(stats.accepted()).isEqualTo((long) ROWS * ROUNDS);
        assertThat(stats.commandsSeen()).isEqualTo((long) ROWS * ROUNDS);
        assertThat(stats.posted()).isEqualTo((long) ROWS * ROUNDS);
        assertThat(stats.postedAmountCents()).isEqualTo(AMOUNT_CENTS * ROWS * ROUNDS);
    }

    private static Callable<Void> afterBarrier(CyclicBarrier barrier, Runnable write) {
        return () -> {
            barrier.await();
            write.run();
            return null;
        };
    }
}
