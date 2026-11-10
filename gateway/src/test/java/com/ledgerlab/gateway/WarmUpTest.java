package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.ledgerlab.gateway.api.TransferWarmUp;
import com.ledgerlab.gateway.persistence.RunPeriodRepository;
import com.ledgerlab.gateway.persistence.TransferRepository;
import com.ledgerlab.gateway.service.TransferBatcher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@TestPropertySource(properties = {"gateway.warm-up.rounds=1000000", "gateway.warm-up.duration=1s", "gateway.warm-up.max-duration=4s"})
@ExtendWith(OutputCaptureExtension.class)
class WarmUpTest extends IntegrationTest {

    @Autowired TransferWarmUp warmUp;
    @Autowired ApplicationAvailability availability;
    @Autowired JdbcClient jdbc;
    @Autowired MeterRegistry registry;
    @Autowired TransferRepository transfers;
    @Autowired RunPeriodRepository periods;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Validator validator;
    @Autowired ApplicationEventPublisher events;

    private interface Pause {
        void run() throws InterruptedException;
    }

    private record OwnWarmUp(TransferWarmUp warmUp, MeterRegistry registry) {
    }

    private long transferRows() {
        return jdbc.sql("SELECT count(*) FROM transfers").query(Long.class).single();
    }

    private double accepted() {
        return registry.counter("gateway.transfers", "outcome", "accepted").count();
    }

    private static void awaitWarm(MeterRegistry registry) {
        await().atMost(Duration.ofSeconds(10)).until(() -> registry.get(TransferWarmUp.WARM).gauge().value() == 1.0);
    }

    /** The context's own warm-up at start goes on behind it up to the long limit; a test's warm-up must not overlap it. */
    @BeforeEach
    void earlierWarmUpDone() {
        awaitWarm(registry);
    }

    @Test
    @DisplayName("the gateway refuses traffic while it warms up, takes it at the short limit and reports itself warm at the long one")
    void the_gateway_refuses_traffic_while_it_warms_up_takes_it_at_the_short_limit_and_reports_itself_warm_at_the_long_one(CapturedOutput output)
        throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            var warming = pool.submit(() -> {
                warmUp.warmUp();
                return null;
            });
            await().atMost(Duration.ofSeconds(5)).until(() -> availability.getReadinessState() == ReadinessState.REFUSING_TRAFFIC);
            await().during(Duration.ofMillis(500)).atMost(Duration.ofMillis(900))
                .until(() -> availability.getReadinessState() == ReadinessState.REFUSING_TRAFFIC);
            warming.get();
        }

        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(registry.get(TransferWarmUp.WARM).gauge().value()).isEqualTo(0.0);
        awaitWarm(registry);
        assertThat(output.getOut()).contains("Warm-up reached its limit of PT4S");
    }

    @Test
    @DisplayName("the warm-up writes no transfer and counts none")
    void the_warm_up_writes_no_transfer_and_counts_none() throws InterruptedException {
        var rows = transferRows();
        var counted = accepted();

        warmUp.warmUp();
        awaitWarm(registry);

        assertThat(transferRows()).isEqualTo(rows);
        assertThat(accepted()).isEqualTo(counted);
    }

    // The stub goes on a mock of the test's own: Mockito stubbing is not thread-safe, and the outbox publisher calls the shared bean meanwhile.
    private OwnWarmUp warmUpWithInsertAfter(Pause pause) {
        var real = AdditionalAnswers.delegatesTo(transfers);
        var stubbed = mock(TransferRepository.class, real);
        doAnswer(call -> {
            pause.run();
            return real.answer(call);
        }).when(stubbed).insertAllIfAbsent(anyList());
        var ownRegistry = new SimpleMeterRegistry();
        return new OwnWarmUp(new TransferWarmUp(stubbed, periods, transactions, objectMapper, validator, events, props, ownRegistry), ownRegistry);
    }

    @Test
    @DisplayName("the warm-up goes through the batch writers and the gateway becomes ready")
    void the_warm_up_goes_through_the_batch_writers_and_the_gateway_becomes_ready() throws InterruptedException {
        var threads = ConcurrentHashMap.<String>newKeySet();
        var own = warmUpWithInsertAfter(() -> threads.add(Thread.currentThread().getName()));

        own.warmUp().warmUp();
        awaitWarm(own.registry());

        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(threads).isNotEmpty().allMatch(name -> name.startsWith(TransferBatcher.WRITER_THREAD));
    }

    @Test
    @DisplayName("a round that ends just past the long limit counts as the limit reached, not as a stuck round")
    void a_round_that_ends_just_past_the_long_limit_counts_as_the_limit_reached_not_as_a_stuck_round(CapturedOutput output)
        throws InterruptedException {
        var slow = warmUpWithInsertAfter(() -> Thread.sleep(100));

        slow.warmUp().warmUp();
        awaitWarm(slow.registry());

        assertThat(output.getOut()).contains("Warm-up reached its limit of PT4S").doesNotContain("did not finish a round");
    }

    @Test
    @DisplayName("a round stuck on the database holds back neither the traffic nor, past the long limit, the runs")
    void a_round_stuck_on_the_database_holds_back_neither_the_traffic_nor_past_the_long_limit_the_runs(CapturedOutput output)
        throws InterruptedException {
        var stuck = new CountDownLatch(1);
        var stuckWarmUp = warmUpWithInsertAfter(stuck::await);
        try {
            var started = System.nanoTime();

            stuckWarmUp.warmUp().warmUp();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
            awaitWarm(stuckWarmUp.registry());
            assertThat(output.getOut()).contains("Warm-up did not finish a round within its limit of PT4S");
        } finally {
            stuck.countDown();
        }
    }
}
