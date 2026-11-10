package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.generator.bank.TransferStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientLatencyTest {

    private static Simulation simulation(FakeGateway gateway, double waitShare, List<Duration> duplicateDelays) {
        return new Simulation(
            UUID.randomUUID(), 20, Duration.ofSeconds(1),
            TestData.profile(20, 2, 3),
            waitShare, Duration.ofSeconds(1), Duration.ofSeconds(5),
            TestData.client(gateway.url()),
            500, new SimpleMeterRegistry(),
            duplicateDelays.isEmpty() ? 0.0 : 1.0, duplicateDelays);
    }

    private static void finish(Simulation simulation) {
        simulation.start();
        await().atMost(Duration.ofSeconds(15)).until(() -> !simulation.state().isActive());
    }

    @Test
    @DisplayName("the client latency is taken over every waiting first press of the run, not its duplicates")
    void the_client_latency_is_taken_over_every_waiting_first_press_of_the_run_not_its_duplicates() {
        // A duplicate goes out 100 ms after the first press and, as a repeat, takes 300 ms.
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofMillis(30)).repeatDelay(Duration.ofMillis(300)).start()) {
            var simulation = simulation(gateway, 1.0, List.of(Duration.ofMillis(100)));

            finish(simulation);

            var status = simulation.status();
            assertThat(status.waitedLatencyP50Ms()).isNotNull().isBetween(30.0, 300.0);
            assertThat(status.waitedLatencyP99Ms()).isNotNull().isGreaterThanOrEqualTo(status.waitedLatencyP50Ms()).isLessThan(300.0);
        }
    }

    @Test
    void a_final_status_to_a_client_that_did_not_wait_gives_no_client_latency() {
        try (var gateway = FakeGateway.with().rawAnswer(request -> FakeGateway.bankAnswer(request, TransferStatus.POSTED, null)).start()) {
            var simulation = simulation(gateway, 0.0, List.of());

            finish(simulation);

            assertThat(simulation.status().acknowledged()).isPositive();
            assertThat(simulation.status().waitedLatencyP50Ms()).isNull();
            assertThat(simulation.status().waitedLatencyP99Ms()).isNull();
        }
    }

    @Test
    @DisplayName("percentiles interpolate between ranks as the bank's SQL does")
    void percentiles_interpolate_between_ranks_as_the_banks_SQL_does() {
        assertThat(Percentiles.percentileCont(List.of(), 0.5)).isNull();
        assertThat(Percentiles.percentileCont(List.of(7L), 0.99)).isEqualTo(7.0);
        assertThat(Percentiles.percentileCont(List.of(10L, 20L, 30L, 40L), 0.5)).isEqualTo(25.0);
        assertThat(Percentiles.percentileCont(LongStream.rangeClosed(1, 100).boxed().toList(), 0.99)).isCloseTo(99.01, within(1e-9));
    }
}
