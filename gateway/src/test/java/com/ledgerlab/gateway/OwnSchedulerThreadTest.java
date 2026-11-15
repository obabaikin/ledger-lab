package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.gateway.kafka.OutboxBatchPublisher;
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
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.TaskSchedulerRouter;

/** A job that holds the shared scheduler's thread, as key cleanup can for seconds, does not stop the commands. */
class OwnSchedulerThreadTest extends IntegrationTest {

    private static final Duration HELD_FOR_LONGER_THAN_THE_TEST = Duration.ofHours(1);
    private static final Duration WELL_BELOW_THE_HOLD = Duration.ofSeconds(10);

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
    void commands_reach_kafka_while_the_shared_thread_is_held() {
        var ageRecords = ageTimer().count();

        var accepted = submit(body());

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        await().atMost(WELL_BELOW_THE_HOLD).until(() ->
            jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE published_at IS NULL", Long.class) == 0L);
        assertThat(ageTimer().count()).isGreaterThan(ageRecords);
    }

    private Timer ageTimer() {
        return registry.get(OutboxBatchPublisher.AGE_METRIC).timer();
    }
}
