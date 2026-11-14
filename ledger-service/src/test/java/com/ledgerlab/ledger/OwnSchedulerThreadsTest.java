package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.ledger.kafka.ResultBatchPublisher;
import com.ledgerlab.ledger.service.LedgerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.TaskSchedulerRouter;

/** A job that holds the shared scheduler's thread, as partition maintenance can for seconds, stops neither results nor credits. */
class OwnSchedulerThreadsTest extends IntegrationTest {

    private static final Duration HELD_FOR_LONGER_THAN_THE_TEST = Duration.ofHours(1);
    private static final Duration WELL_BELOW_THE_HOLD = Duration.ofSeconds(10);

    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;
    @Autowired MeterRegistry registry;
    @Autowired @Qualifier(TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME) TaskScheduler shared;

    private final CountDownLatch release = new CountDownLatch(1);
    private ScheduledFuture<?> holder;

    @BeforeEach
    void holdTheSharedThread() throws InterruptedException {
        var holding = new CountDownLatch(1);
        holder = shared.scheduleWithFixedDelay(() -> {
            holding.countDown();
            try {
                release.await(HELD_FOR_LONGER_THAN_THE_TEST.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, HELD_FOR_LONGER_THAN_THE_TEST);
        assertThat(holding.await(WELL_BELOW_THE_HOLD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    @AfterEach
    void releaseTheSharedThread() {
        holder.cancel(false);
        release.countDown();
    }

    @Test
    void results_are_published_while_the_shared_thread_is_held() {
        openBank(3, 1_000);
        var ageRecords = ageTimer().count();

        applyOne(ledger, transfer(1, 2, 100));

        await().atMost(WELL_BELOW_THE_HOLD).until(() ->
            jdbc.sql("SELECT count(*) FROM transfers WHERE result_published_at IS NULL").query(Long.class).single() == 0L);
        assertThat(ageTimer().count()).isGreaterThan(ageRecords);
    }

    @Test
    void credits_are_applied_while_the_shared_thread_is_held() {
        openBank(3, 1_000);

        applyOne(ledger, transfer(2, 1, 100));

        await().atMost(WELL_BELOW_THE_HOLD).until(() -> pendingCredits() == 0L);
        assertThat(balances().get(1)).isEqualTo(1_100);
    }

    private Timer ageTimer() {
        return registry.get(ResultBatchPublisher.AGE_METRIC).timer();
    }
}
