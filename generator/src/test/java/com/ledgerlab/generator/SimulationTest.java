package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulationTest {

    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(5);

    private static void assertSettledCounts(SimulationStatus status) {
        assertThat(status.inFlight()).isZero();
        assertThat(status.acknowledged() + status.failed() + status.unknown()).isEqualTo(status.requested());
    }

    private static List<JournalEntryView> entries(Simulation simulation) {
        return Objects.requireNonNull(simulation.journal()).page(TestData.anyEntry(), 0, Integer.MAX_VALUE, true).items();
    }

    private static long resends(List<JournalEntryView> entries, ResendKind kind) {
        return entries.stream().flatMap(it -> it.resends().stream()).filter(it -> it.kind() == kind).count();
    }

    /** The journal and the counters are kept apart; they must tell the same story. */
    private static void assertJournalMatchesStatus(Simulation simulation) {
        var status = simulation.status();
        var entries = entries(simulation);
        assertThat(entries).hasSize((int) status.requested());
        assertThat(entries).extracting(JournalEntryView::seq).containsExactlyElementsOf(LongStream.rangeClosed(1, entries.size()).boxed().toList());
        assertThat(entries).extracting(JournalEntryView::transferId).doesNotHaveDuplicates();
        assertThat(entries).allSatisfy(it -> assertThat(it.first()).isNotNull());
        var byOutcome = new EnumMap<JournalOutcome, Long>(JournalOutcome.class);
        entries.forEach(it -> byOutcome.merge(it.outcome(), 1L, Long::sum));
        assertThat(byOutcome.get(JournalOutcome.PENDING)).isNull();
        assertThat(byOutcome.getOrDefault(JournalOutcome.ACKNOWLEDGED, 0L)).isEqualTo(status.acknowledged());
        assertThat(byOutcome.getOrDefault(JournalOutcome.FAILED, 0L)).isEqualTo(status.failed());
        assertThat(byOutcome.getOrDefault(JournalOutcome.UNKNOWN, 0L)).isEqualTo(status.unknown());
        assertThat(resends(entries, ResendKind.RETRY)).isEqualTo(status.retries());
        assertThat(resends(entries, ResendKind.DUPLICATE)).isEqualTo(status.duplicatesSent());
        assertThat(entries.stream().filter(it -> it.gatewayStatus() == TransferStatus.POSTED).count()).isEqualTo(status.posted());
        assertThat(entries.stream().filter(it -> it.gatewayStatus() == TransferStatus.REJECTED).count()).isEqualTo(status.rejected());
        var byReason = new EnumMap<RejectReason, Long>(RejectReason.class);
        entries.stream().map(JournalEntryView::rejectReason).filter(Objects::nonNull).forEach(reason -> byReason.merge(reason, 1L, Long::sum));
        assertThat(byReason).isEqualTo(status.rejectedByReason());
        assertThat(status.rejectedByReason().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(status.rejected());
    }

    private static Simulation simulation(GatewayClient client, int tps, long seconds) {
        return simulation(client, tps, seconds, 0.0, DEFAULT_GRACE);
    }

    private static Simulation simulation(GatewayClient client, int tps, long seconds, double waitShare, Duration stopGrace) {
        return simulation(client, tps, seconds, waitShare, stopGrace, List.of(), new SimpleMeterRegistry());
    }

    private static Simulation withDuplicates(GatewayClient client, int tps, long seconds, Duration stopGrace, Duration... duplicateDelays) {
        return simulation(client, tps, seconds, 0.0, stopGrace, List.of(duplicateDelays), new SimpleMeterRegistry());
    }

    private static Simulation simulation(
        GatewayClient client, int tps, long seconds, double waitShare, Duration stopGrace, List<Duration> duplicateDelays, MeterRegistry registry) {
        return new Simulation(
            UUID.randomUUID(), tps, Duration.ofSeconds(seconds),
            TestData.profile(20, 2, 3),
            waitShare, Duration.ofSeconds(1), stopGrace, client,
            500, registry, duplicateDelays.isEmpty() ? 0.0 : 1.0, duplicateDelays);
    }

    private static void awaitState(Simulation simulation, SimulationState state, Duration atMost) {
        await().atMost(atMost).until(() -> simulation.state() == state);
    }

    private static void awaitFinished(Simulation simulation) {
        awaitState(simulation, SimulationState.FINISHED, Duration.ofSeconds(15));
    }

    @Test
    void holds_the_requested_rate_and_gets_every_submission_acknowledged() {
        try (var gateway = new FakeGateway()) {
            var simulation = simulation(TestData.client(gateway.url()), 200, 2, 0.2, DEFAULT_GRACE);
            simulation.start();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(status.requested()).isBetween(360L, 440L);
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(status.failed()).isZero();
            assertThat(status.unknown()).isZero();
            assertThat(status.retries()).isZero();
            assertThat(status.posted()).isGreaterThan(0);
            assertThat(gateway.attemptsById).hasSize((int) status.requested());
            assertThat(gateway.attempts()).containsOnly(1);
            assertJournalMatchesStatus(simulation);
            assertThat(entries(simulation)).allSatisfy(it -> {
                assertThat(it.first().result()).isEqualTo(SendResult.RESPONSE);
                assertThat(it.first().httpStatus()).isIn(200, 202);
                assertThat(it.resends()).isEmpty();
            });
        }
    }

    @Test
    @DisplayName("counts new transfers per second of the run, a slot for each second begun, adding up to the requested")
    void counts_new_transfers_per_second_of_the_run_a_slot_for_each_second_begun_adding_up_to_the_requested() throws InterruptedException {
        try (var gateway = new FakeGateway()) {
            var simulation = simulation(TestData.client(gateway.url()), 200, 3);
            simulation.start();
            Thread.sleep(1_500);
            var midway = simulation.status().requestedPerSecond();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(midway).hasSize(2);
            // The last transfer is due just before the third second ends: a fourth slot exists only if its answer came after it.
            assertThat(status.requestedPerSecond()).hasSizeBetween(3, 4);
            assertThat(status.requestedPerSecond().stream().mapToLong(Long::longValue).sum()).isEqualTo(status.requested());
            assertThat(status.requestedPerSecond().subList(0, 3)).allSatisfy(it -> assertThat(it).isBetween(150L, 250L));
        }
    }

    @Test
    void retries_a_failed_submission_with_the_same_transfer_id() {
        try (var gateway = FakeGateway.with().failFirstAttempt().start()) {
            var simulation = simulation(TestData.client(gateway.url()), 50, 1);
            simulation.start();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(status.failed()).isZero();
            assertThat(status.retries()).isEqualTo(status.requested());
            assertThat(gateway.attempts()).containsOnly(2);
            assertJournalMatchesStatus(simulation);
            assertThat(entries(simulation)).allSatisfy(entry -> {
                assertThat(entry.first().httpStatus()).isEqualTo(503);
                assertThat(entry.resends()).singleElement().satisfies(resend -> {
                    assertThat(resend.kind()).isEqualTo(ResendKind.RETRY);
                    assertThat(resend.number()).isEqualTo(2);
                    assertThat(resend.result()).isEqualTo(SendResult.RESPONSE);
                    assertThat(resend.httpStatus()).isEqualTo(202);
                    assertThat(resend.offsetMs()).isGreaterThanOrEqualTo(entry.first().offsetMs() + entry.first().latencyMs());
                });
            });
        }
    }

    @Test
    @DisplayName("a 5xx on the last attempt leaves the outcome unknown, not failed")
    void a_5xx_on_the_last_attempt_leaves_the_outcome_unknown_not_failed() {
        try (var gateway = FakeGateway.with().failFirstAttempt().start()) {
            var simulation = simulation(TestData.client(gateway.url(), 1, Duration.ofMillis(50)), 20, 1);
            simulation.start();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(status.unknown()).isEqualTo(status.requested());
            assertThat(status.failed()).isZero();
            assertThat(status.acknowledged()).isZero();
            assertSettledCounts(status);
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    @DisplayName("a 503 busy on every attempt is a failure, not unknown, and the transfer is counted as refused once")
    void a_503_busy_on_every_attempt_is_a_failure_not_unknown_and_the_transfer_is_counted_as_refused_once() {
        try (var gateway = FakeGateway.with().busyAttempts(2).start()) {
            var simulation = simulation(TestData.client(gateway.url(), 2, Duration.ofMillis(50)), 20, 1);
            simulation.start();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(status.failed()).isEqualTo(status.requested());
            assertThat(status.unknown()).isZero();
            assertThat(status.refused()).isEqualTo(status.requested());
            assertSettledCounts(status);
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    @DisplayName("after a 503 busy the retry waits for Retry-After and is taken")
    void after_a_503_busy_the_retry_waits_for_Retry_After_and_is_taken() {
        try (var gateway = FakeGateway.with().busyAttempts(1).busyRetryAfterSeconds(1).start()) {
            var simulation = simulation(TestData.client(gateway.url()), 20, 1);
            simulation.start();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(status.refused()).isEqualTo(status.requested());
            assertThat(status.retries()).isEqualTo(status.requested());
            assertThat(entries(simulation)).allSatisfy(entry ->
                assertThat(entry.resends()).filteredOn(it -> it.kind() == ResendKind.RETRY).singleElement()
                    .satisfies(retry -> assertThat(retry.offsetMs() - entry.first().offsetMs()).isGreaterThanOrEqualTo(1_000)));
            assertSettledCounts(status);
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    void a_4xx_is_a_failure_and_is_not_retried() {
        try (var gateway = FakeGateway.with().refuseWith(422).start()) {
            var simulation = simulation(TestData.client(gateway.url()), 20, 1);
            simulation.start();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(status.failed()).isEqualTo(status.requested());
            assertThat(status.unknown()).isZero();
            assertThat(status.retries()).isZero();
            assertSettledCounts(status);
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    void a_stop_lets_open_requests_finish_so_the_counters_add_up() {
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofMillis(300)).start()) {
            var simulation = simulation(TestData.client(gateway.url()), 100, 30);
            simulation.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> simulation.status().inFlight() > 10);

            simulation.stop();
            assertThat(simulation.state()).isEqualTo(SimulationState.STOPPING);
            awaitState(simulation, SimulationState.STOPPED, Duration.ofSeconds(10));

            var status = simulation.status();
            assertThat(status.inFlight()).isZero();
            assertThat(status.failed()).isZero();
            assertThat(status.unknown()).isZero();
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(gateway.requests.get()).isEqualTo((int) status.requested());
        }
    }

    @Test
    void requests_still_open_after_the_grace_period_are_cancelled_and_counted_as_unknown() {
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofSeconds(5)).start()) {
            var simulation = simulation(TestData.client(gateway.url()), 100, 30, 0.0, Duration.ofMillis(200));
            simulation.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> simulation.status().inFlight() > 10);

            var stopStarted = System.nanoTime();
            simulation.stop();
            awaitState(simulation, SimulationState.STOPPED, Duration.ofSeconds(5));

            // The answers would take five seconds: the stop ends at the grace, not when they come.
            assertThat(Duration.ofNanos(System.nanoTime() - stopStarted)).isBetween(Duration.ofMillis(200), Duration.ofSeconds(2));
            var status = simulation.status();
            assertThat(status.failed()).isZero();
            assertThat(status.unknown()).isGreaterThan(0);
            assertSettledCounts(status);
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    @DisplayName("no retries after a stop, and an unanswered 5xx stays unknown")
    void no_retries_after_a_stop_and_an_unanswered_5xx_stays_unknown() {
        try (var gateway = FakeGateway.with().failFirstAttempt().start()) {
            var simulation = simulation(TestData.client(gateway.url(), 3, Duration.ofSeconds(1)), 50, 30);
            simulation.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> simulation.status().requested() > 10);

            simulation.stop();
            awaitState(simulation, SimulationState.STOPPED, Duration.ofSeconds(10));

            var status = simulation.status();
            assertThat(gateway.attempts()).containsOnly(1);
            assertThat(status.unknown()).isEqualTo(status.requested());
            assertThat(status.failed()).isZero();
            assertSettledCounts(status);
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    void a_ledger_rejection_keeps_its_reason_in_the_journal_and_in_the_status() {
        try (var gateway = FakeGateway.with().rejectReason(RejectReason.INSUFFICIENT_FUNDS).start()) {
            var simulation = simulation(TestData.client(gateway.url()), 20, 1, 1.0, DEFAULT_GRACE);
            simulation.start();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(status.rejected()).isEqualTo(status.requested());
            assertThat(status.rejectedByReason()).containsExactly(entry(RejectReason.INSUFFICIENT_FUNDS, status.requested()));
            assertJournalMatchesStatus(simulation);
            assertThat(entries(simulation)).allSatisfy(it -> {
                assertThat(it.outcome()).isEqualTo(JournalOutcome.ACKNOWLEDGED);
                assertThat(it.gatewayStatus()).isEqualTo(TransferStatus.REJECTED);
                assertThat(it.rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);
            });
        }
    }

    @Test
    void a_send_cut_off_by_the_grace_period_is_recorded_as_cancelled() {
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofSeconds(5)).start()) {
            var simulation = simulation(TestData.client(gateway.url()), 100, 30, 0.0, Duration.ofMillis(200));
            simulation.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> simulation.status().inFlight() > 10);

            simulation.stop();
            awaitState(simulation, SimulationState.STOPPED, Duration.ofSeconds(5));

            var cancelled = entries(simulation).stream().filter(it -> it.first().result() == SendResult.CANCELLED).toList();
            assertThat(cancelled).isNotEmpty();
            assertThat(cancelled).allSatisfy(it -> {
                assertThat(it.outcome()).isEqualTo(JournalOutcome.UNKNOWN);
                assertThat(it.first().httpStatus()).isNull();
            });
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    void entries_are_readable_while_the_run_is_going_and_start_as_pending() {
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofMillis(500)).start()) {
            var simulation = simulation(TestData.client(gateway.url()), 50, 30);
            simulation.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> simulation.status().inFlight() > 5);

            var page = Objects.requireNonNull(simulation.journal()).page(new JournalFilter(JournalOutcome.PENDING, null, null), 0, 1_000, false);
            assertThat(page.items()).isNotEmpty();
            assertThat(page.next()).isNotNull();

            simulation.stop();
            awaitState(simulation, SimulationState.STOPPED, Duration.ofSeconds(10));
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    @DisplayName("a released journal is gone, the status stays")
    void a_released_journal_is_gone_the_status_stays() {
        try (var gateway = new FakeGateway()) {
            var simulation = simulation(TestData.client(gateway.url()), 20, 1);
            simulation.start();
            assertThatThrownBy(simulation::releaseJournal).isInstanceOf(IllegalStateException.class);
            awaitFinished(simulation);

            simulation.releaseJournal();

            assertThat(simulation.journal()).isNull();
            assertThat(simulation.status().requested()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("a duplicate is a second send of the same transfer, not a second transfer")
    void a_duplicate_is_a_second_send_of_the_same_transfer_not_a_second_transfer() {
        try (var gateway = new FakeGateway()) {
            var registry = new SimpleMeterRegistry();
            var simulation = simulation(
                TestData.client(gateway.url()), 50, 1, 1.0, DEFAULT_GRACE, List.of(Duration.ZERO, Duration.ofMillis(50)), registry);
            simulation.start();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(status.requested()).isEqualTo(50);
            assertThat(status.duplicatesSent()).isEqualTo(status.requested());
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(status.retries()).isZero();
            assertThat(gateway.attemptsById).hasSize(50);
            assertThat(gateway.attempts()).containsOnly(2);
            assertJournalMatchesStatus(simulation);
            assertThat(entries(simulation)).allSatisfy(it ->
                assertThat(it.resends()).singleElement().satisfies(resend -> assertThat(resend.kind()).isEqualTo(ResendKind.DUPLICATE)));
            assertThat(sends(registry, "first")).isEqualTo(50);
            assertThat(sends(registry, "duplicate")).isEqualTo(50);
            assertThat(registry.get("generator.submission.latency").tag("kind", "duplicate").timer().count()).isEqualTo(50);
        }
    }

    private static long sends(MeterRegistry registry, String kind) {
        return registry.get("generator.send.latency").tags("waited", "true", "kind", kind, "final", "true").timer().count();
    }

    @Test
    void an_answer_to_either_send_acknowledges_the_transfer() {
        // Whichever send of a transfer arrives first gets the 503; with a single attempt alone it would stay unknown.
        try (var gateway = FakeGateway.with().failFirstAttempt().start()) {
            var simulation = withDuplicates(TestData.client(gateway.url(), 1, Duration.ofMillis(50)), 20, 1, DEFAULT_GRACE, Duration.ZERO);
            simulation.start();
            awaitFinished(simulation);

            var status = simulation.status();
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(status.unknown()).isZero();
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    void a_stop_cancels_duplicates_that_are_still_waiting_for_their_moment() {
        try (var gateway = new FakeGateway()) {
            var simulation = withDuplicates(TestData.client(gateway.url()), 50, 30, DEFAULT_GRACE, Duration.ofSeconds(20));
            simulation.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> simulation.status().requested() > 10);

            simulation.stop();
            awaitState(simulation, SimulationState.STOPPED, Duration.ofSeconds(3));

            var status = simulation.status();
            assertThat(status.duplicatesSent()).isZero();
            assertThat(gateway.attempts()).containsOnly(1);
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    void the_pacer_never_launches_more_than_the_journal_reserved() {
        // 1s / 7 is rounded down to whole nanoseconds, which would make a 22nd send due just before the end.
        try (var gateway = new FakeGateway()) {
            var simulation = simulation(TestData.client(gateway.url()), 7, 3);
            simulation.start();
            awaitFinished(simulation);

            assertThat(simulation.status().requested()).isEqualTo(simulation.journalCapacity()).isEqualTo(21);
        }
    }

    @Test
    void a_duplicate_cut_off_by_a_stop_does_not_undo_the_answer_the_first_send_got() {
        try (var gateway = FakeGateway.with().repeatDelay(Duration.ofSeconds(5)).start()) {
            var simulation = withDuplicates(TestData.client(gateway.url()), 50, 30, Duration.ofMillis(300), Duration.ofMillis(50));
            simulation.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> simulation.status().duplicatesSent() > 5);

            simulation.stop();
            awaitState(simulation, SimulationState.STOPPED, Duration.ofSeconds(5));

            var status = simulation.status();
            assertThat(status.unknown()).isZero();
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(entries(simulation)).flatExtracting(JournalEntryView::resends).extracting(Resend::result).contains(SendResult.CANCELLED);
            assertJournalMatchesStatus(simulation);
        }
    }

    @Test
    void a_second_stop_changes_nothing() {
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofMillis(300)).start()) {
            var simulation = simulation(TestData.client(gateway.url()), 100, 30);
            simulation.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> simulation.status().inFlight() > 10);

            simulation.stop();
            simulation.stop();
            awaitState(simulation, SimulationState.STOPPED, Duration.ofSeconds(10));
            var finishedAt = simulation.finishedAt();
            simulation.stop();

            assertThat(simulation.state()).isEqualTo(SimulationState.STOPPED);
            assertThat(simulation.finishedAt()).isEqualTo(finishedAt);
            assertSettledCounts(simulation.status());
        }
    }

    @Test
    void the_journal_holds_a_run_whose_size_does_not_fit_an_int() {
        assertThat(Simulation.journalCapacity(2_000, Duration.ofSeconds(1_100_000))).isEqualTo(2_200_000_000L);
    }
}
