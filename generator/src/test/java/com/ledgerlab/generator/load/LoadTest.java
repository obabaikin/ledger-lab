package com.ledgerlab.generator.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.generator.FakeGateway;
import com.ledgerlab.generator.TestData;
import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import com.ledgerlab.generator.gateway.GatewayClient;
import com.ledgerlab.generator.gateway.ResponseKind;
import com.ledgerlab.generator.journal.JournalOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LoadTest {

    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(5);

    private final RecordingJournal journal = new RecordingJournal();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MemoryMinuteStore minuteStore = new MemoryMinuteStore();
    private Load load;

    private Load load(TestData.Settings settings) {
        return load(settings, DEFAULT_GRACE);
    }

    private Load load(TestData.Settings settings, Duration stopGrace) {
        return load(settings, stopGrace, Clock.systemUTC());
    }

    private Load load(TestData.Settings settings, Duration stopGrace, Clock clock) {
        return load(settings, stopGrace, clock, SendPermission.ALWAYS);
    }

    private Load load(TestData.Settings settings, Duration stopGrace, Clock clock, SendPermission permission) {
        var props = settings.build();
        var schedule = new BaseSchedule(props);
        load = new Load(props, new GatewayClient(props, new ObjectMapper(), registry), journal, new ResponseMinutes(clock, props, schedule, minuteStore), schedule, permission, clock, registry, stopGrace);
        return load;
    }

    @AfterEach
    void stopTheLoad() {
        if (load != null) {
            load.stop();
        }
    }

    private static void runFor(Load load, Duration time) throws InterruptedException {
        load.begin();
        Thread.sleep(time);
        load.stop();
    }

    private long responses(ResponseKind kind) {
        return (long) registry.get(Load.RESPONSES_METRIC).tag(Load.KIND_TAG, kind.tag()).counter().count();
    }

    /**
     * Transfers by the number of their requests the bank saw. A stop cuts off the retry or the duplicate of a
     * transfer that was between its two sends, so a test of two sends counts the transfers that got both.
     */
    private static long sentTimes(FakeGateway gateway, int times) {
        return gateway.attempts().stream().filter(attempts -> attempts == times).count();
    }

    private long sends(String kind) {
        return registry.get("generator.send.latency").tags("waited", "true", "kind", kind, "final", "true").timer().count();
    }

    /** The journal and the counters are kept apart; they must tell the same story. */
    private void assertJournalMatches(LoadStatus status) {
        assertThat(status.inFlight()).isZero();
        assertThat(status.acknowledged() + status.failed() + status.unknown()).isEqualTo(status.requested());
        assertThat(journal.intents).hasSize((int) status.requested());
        assertThat(journal.count(JournalOutcome.ACKNOWLEDGED)).isEqualTo(status.acknowledged());
        assertThat(journal.count(JournalOutcome.FAILED)).isEqualTo(status.failed());
        assertThat(journal.withoutOutcome()).hasSize((int) status.unknown());
        assertThat(journal.outcomes.values().stream().filter(it -> it.bankStatus() == TransferStatus.POSTED).count()).isEqualTo(status.posted());
        assertThat(journal.outcomes.values().stream().filter(it -> it.bankStatus() == TransferStatus.REJECTED).count()).isEqualTo(status.rejected());
        assertThat(status.rejectedByReason().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(status.rejected());
    }

    @Test
    void sends_nothing_while_another_instance_holds_the_lease_and_starts_at_the_pace_without_catching_up_once_it_holds_it() throws Exception {
        try (var gateway = new FakeGateway()) {
            var granted = new AtomicBoolean(false);
            var load = load(TestData.settings(gateway.url()).tps(200), DEFAULT_GRACE, Clock.systemUTC(), granted::get);

            load.begin();
            Thread.sleep(1_000);
            assertThat(load.status().requested()).isZero();
            granted.set(true);
            Thread.sleep(1_000);
            load.stop();

            assertThat(load.status().requested()).isBetween(150L, 220L);
            assertThat(gateway.attemptsById).hasSize((int) load.status().requested());
        }
    }

    @Test
    void a_stop_adds_the_answers_still_counted_in_memory_to_the_minutes() throws Exception {
        try (var gateway = new FakeGateway()) {
            var load = load(TestData.settings(gateway.url()).tps(200));
            runFor(load, Duration.ofSeconds(1));

            var counted = minuteStore.between(Instant.EPOCH, Instant.now()).stream().mapToLong(MinuteCounts::total).sum();
            assertThat(counted).isPositive().isEqualTo(responses(ResponseKind.ACCEPTED));
        }
    }

    @Test
    void holds_the_target_rate_and_gets_every_submission_acknowledged() throws Exception {
        try (var gateway = new FakeGateway()) {
            var load = load(TestData.settings(gateway.url()).tps(200).waitShare(0.2));
            runFor(load, Duration.ofSeconds(2));

            var status = load.status();
            assertThat(status.startedAt()).isNotNull();
            assertThat(status.baseTps()).isEqualTo(200);
            assertThat(status.requested()).isBetween(340L, 440L);
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(status.failed()).isZero();
            assertThat(status.unknown()).isZero();
            assertThat(status.retries()).isZero();
            assertThat(status.skipped()).isZero();
            assertThat(status.posted()).isGreaterThan(0);
            assertThat(gateway.attemptsById).hasSize((int) status.requested());
            assertThat(gateway.attempts()).containsOnly(1);
            assertThat(responses(ResponseKind.ACCEPTED)).isEqualTo(status.requested());
            assertJournalMatches(status);
            assertThat(journal.outcomes.values()).allSatisfy(it -> assertThat(it.acceptedAt()).isNotNull());
        }
    }

    @Test
    void the_intent_of_a_transfer_is_in_the_journal_before_its_request_reaches_the_bank() throws Exception {
        var arrivedWithoutIntent = new ConcurrentLinkedQueue<UUID>();
        try (var gateway = FakeGateway.with().onArrival(request -> {
            if (!journal.intents.containsKey(request.transferId())) {
                arrivedWithoutIntent.add(request.transferId());
            }
        }).start()) {
            var load = load(TestData.settings(gateway.url()).tps(200));
            runFor(load, Duration.ofSeconds(1));

            assertThat(gateway.requests.get()).isGreaterThan(100);
            assertThat(arrivedWithoutIntent).isEmpty();
        }
    }

    @Test
    void while_the_journal_cannot_store_intents_nothing_is_sent_and_the_transfers_are_counted() throws Exception {
        try (var gateway = new FakeGateway()) {
            journal.unavailable = true;
            var load = load(TestData.settings(gateway.url()).tps(200));
            runFor(load, Duration.ofMillis(500));

            var status = load.status();
            assertThat(gateway.requests.get()).isZero();
            assertThat(status.notJournaled()).isGreaterThan(50);
            assertThat(status.requested()).isZero();
            assertThat(journal.intents).isEmpty();
        }
    }

    @Test
    @DisplayName("a pulse raises the pace, and the load returns to its base when it is over")
    void a_pulse_raises_the_pace_and_the_load_returns_to_its_base_when_it_is_over() throws Exception {
        try (var gateway = new FakeGateway()) {
            var load = load(TestData.settings(gateway.url()).tps(50));
            load.begin();
            Thread.sleep(200);

            var beforePulse = load.status().requested();
            load.pulse(500, Duration.ofSeconds(1));
            assertThat(load.status().targetTps()).isEqualTo(500);
            assertThat(load.status().pulse()).isNotNull().satisfies(pulse -> assertThat(pulse.tps()).isEqualTo(500));
            Thread.sleep(1_000);
            var duringPulse = load.status().requested() - beforePulse;

            await().atMost(Duration.ofSeconds(2)).until(() -> load.status().pulse() == null);
            assertThat(load.status().targetTps()).isEqualTo(50);
            var afterPulse = load.status().requested();
            Thread.sleep(1_000);
            var atBase = load.status().requested() - afterPulse;

            assertThat(duringPulse).isBetween(400L, 560L);
            assertThat(atBase).isBetween(35L, 70L);
        }
    }

    @Test
    @DisplayName("of two pulses at once the highest sets the pace: they do not add up, and the lower one goes on when the higher ends")
    void of_two_pulses_at_once_the_highest_sets_the_pace_they_do_not_add_up_and_the_lower_one_goes_on_when_the_higher_ends() {
        var load = load(TestData.settings("http://localhost:1").tps(50));

        load.pulse(300, Duration.ofMillis(400));
        load.pulse(200, Duration.ofSeconds(30));

        assertThat(load.status().targetTps()).isEqualTo(300);
        await().atMost(Duration.ofSeconds(2)).until(() -> load.status().targetTps() == 200);
        assertThat(load.status().pulse()).satisfies(pulse -> assertThat(pulse.tps()).isEqualTo(200));
    }

    @Test
    @DisplayName("with the profile, a pulse of 900 over a base of 800 sets 900, and once over the load is the base of the step it is in by then")
    void with_the_profile_a_pulse_over_the_base_sets_the_pace_and_once_over_the_load_is_the_base_of_the_step_it_is_in_by_then() {
        var settings = TestData.settings("http://localhost:1").baseProfile(TestData.dayProfile(11));
        var schedule = new BaseSchedule(settings.build());
        var now = new AtomicReference<>(Instant.parse("2026-11-16T05:00:00Z"));
        while (schedule.baseAt(now.get()) != 800) {
            now.set(now.get().plus(Duration.ofMinutes(5)));
        }
        var load = load(settings, DEFAULT_GRACE, new MovableClock(now));

        load.pulse(900, Duration.ofMillis(300));
        assertThat(load.status().baseTps()).isEqualTo(800);
        assertThat(load.status().targetTps()).isEqualTo(900);

        now.set(now.get().plus(Duration.ofMinutes(5)));
        var nextBase = schedule.baseAt(now.get());
        await().atMost(Duration.ofSeconds(2)).until(() -> load.status().pulse() == null);
        assertThat(load.status().baseTps()).isEqualTo(nextBase);
        assertThat(load.status().targetTps()).isEqualTo(nextBase);
        assertThat(load.status().nextChangeAt()).isEqualTo(now.get().plus(Duration.ofMinutes(5)));
    }

    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now;

        MovableClock(AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void without_the_profile_the_base_is_tps_and_has_no_next_change() {
        var load = load(TestData.settings("http://localhost:1").tps(100));

        assertThat(load.status().baseTps()).isEqualTo(100);
        assertThat(load.status().nextChangeAt()).isNull();
    }

    @Test
    void a_pulse_below_the_base_does_not_lower_the_pace() {
        var load = load(TestData.settings("http://localhost:1").tps(100));

        load.pulse(40, Duration.ofSeconds(30));

        assertThat(load.status().targetTps()).isEqualTo(100);
    }

    @Test
    @DisplayName("a pulse above the ceiling, without a pace, without a length or longer than allowed is refused")
    void a_pulse_above_the_ceiling_without_a_pace_without_a_length_or_longer_than_allowed_is_refused() {
        var load = load(TestData.settings("http://localhost:1").tps(100));

        load.pulse(2_000, Duration.ofMinutes(10));
        assertThatThrownBy(() -> load.pulse(2_001, Duration.ofSeconds(1))).isInstanceOf(InvalidPulseException.class);
        assertThatThrownBy(() -> load.pulse(0, Duration.ofSeconds(1))).isInstanceOf(InvalidPulseException.class);
        assertThatThrownBy(() -> load.pulse(500, Duration.ZERO)).isInstanceOf(InvalidPulseException.class);
        assertThatThrownBy(() -> load.pulse(500, Duration.ofMinutes(10).plusSeconds(1))).isInstanceOf(InvalidPulseException.class);
    }

    @Test
    @DisplayName("with as many requests open as allowed, a transfer that is due is skipped and counted, not queued")
    void with_as_many_requests_open_as_allowed_a_transfer_that_is_due_is_skipped_and_counted_not_queued() throws Exception {
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofMillis(400)).start()) {
            var load = load(TestData.settings(gateway.url()).tps(100).maxInFlight(5));
            load.begin();
            Thread.sleep(1_000);
            var open = load.status().inFlight();
            load.stop();

            var status = load.status();
            assertThat(open).isLessThanOrEqualTo(5);
            // Five at a time, each for 0.4 s: about a dozen in the second, the rest of the hundred due were skipped.
            assertThat(status.requested()).isBetween(5L, 20L);
            assertThat(status.skipped()).isBetween(60L, 100L);
            assertJournalMatches(status);
        }
    }

    @Test
    void retries_a_failed_submission_with_the_same_transfer_id() throws Exception {
        try (var gateway = FakeGateway.with().failFirstAttempt().start()) {
            var load = load(TestData.settings(gateway.url()).tps(50));
            runFor(load, Duration.ofSeconds(1));

            var status = load.status();
            assertThat(status.requested()).isGreaterThan(20);
            assertThat(status.failed()).isZero();
            assertThat(status.retries()).isEqualTo(sentTimes(gateway, 2)).isGreaterThan(status.requested() / 2);
            assertThat(status.acknowledged()).isEqualTo(status.retries());
            assertThat(status.unknown()).as("the stop came before their retry").isEqualTo(sentTimes(gateway, 1));
            assertThat(gateway.attemptsById).hasSize((int) status.requested());
            assertThat(responses(ResponseKind.SERVER_ERROR)).isEqualTo(status.requested());
            assertThat(responses(ResponseKind.ACCEPTED)).isEqualTo(status.retries());
            assertJournalMatches(status);
        }
    }

    @Test
    @DisplayName("a 5xx on the last attempt leaves the outcome unknown, not failed, and the journal has no outcome for it")
    void a_5xx_on_the_last_attempt_leaves_the_outcome_unknown_not_failed_and_the_journal_has_no_outcome_for_it() throws Exception {
        try (var gateway = FakeGateway.with().failFirstAttempt().start()) {
            var load = load(TestData.settings(gateway.url()).tps(20).maxAttempts(1));
            runFor(load, Duration.ofSeconds(1));

            var status = load.status();
            assertThat(status.requested()).isGreaterThan(0);
            assertThat(status.unknown()).isEqualTo(status.requested());
            assertThat(status.failed()).isZero();
            assertThat(status.acknowledged()).isZero();
            assertThat(journal.outcomes).isEmpty();
            assertJournalMatches(status);
        }
    }

    @Test
    @DisplayName("a 503 busy on every attempt is a failure, not unknown, and the transfer is counted as refused once")
    void a_503_busy_on_every_attempt_is_a_failure_not_unknown_and_the_transfer_is_counted_as_refused_once() throws Exception {
        try (var gateway = FakeGateway.with().busyAttempts(2).start()) {
            var load = load(TestData.settings(gateway.url()).tps(20).maxAttempts(2));
            runFor(load, Duration.ofSeconds(1));

            var status = load.status();
            assertThat(status.requested()).isGreaterThan(0);
            assertThat(status.failed()).isEqualTo(status.requested());
            assertThat(status.unknown()).isZero();
            assertThat(status.refused()).isEqualTo(status.requested());
            assertThat(responses(ResponseKind.BUSY)).isEqualTo(sentTimes(gateway, 1) + 2 * sentTimes(gateway, 2));
            assertThat(sentTimes(gateway, 2)).isGreaterThan(status.requested() / 2);
            assertJournalMatches(status);
        }
    }

    @Test
    @DisplayName("a 503 stopping on every attempt is a failure, not unknown, and is not counted as a busy refusal")
    void a_503_stopping_on_every_attempt_is_a_failure_not_unknown_and_is_not_counted_as_a_busy_refusal() throws Exception {
        try (var gateway = FakeGateway.with().stoppingAttempts(2).start()) {
            var load = load(TestData.settings(gateway.url()).tps(20).maxAttempts(2));
            runFor(load, Duration.ofSeconds(1));

            var status = load.status();
            assertThat(status.requested()).isGreaterThan(0);
            assertThat(status.failed()).isEqualTo(status.requested());
            assertThat(status.unknown()).isZero();
            assertThat(status.refused()).isZero();
            assertThat(responses(ResponseKind.STOPPING)).isEqualTo(sentTimes(gateway, 1) + 2 * sentTimes(gateway, 2));
            assertThat(sentTimes(gateway, 2)).isGreaterThan(status.requested() / 2);
            assertThat(responses(ResponseKind.BUSY)).isZero();
            assertJournalMatches(status);
        }
    }

    @Test
    void a_4xx_is_a_failure_and_is_not_retried() throws Exception {
        try (var gateway = FakeGateway.with().refuseWith(422).start()) {
            var load = load(TestData.settings(gateway.url()).tps(20));
            runFor(load, Duration.ofSeconds(1));

            var status = load.status();
            assertThat(status.requested()).isGreaterThan(0);
            assertThat(status.failed()).isEqualTo(status.requested());
            assertThat(status.unknown()).isZero();
            assertThat(status.retries()).isZero();
            assertThat(responses(ResponseKind.INVALID)).isEqualTo(status.requested());
            assertJournalMatches(status);
        }
    }

    @Test
    void a_stop_lets_open_requests_finish_so_the_counters_add_up() throws Exception {
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofMillis(300)).start()) {
            var load = load(TestData.settings(gateway.url()).tps(100));
            load.begin();
            await().atMost(Duration.ofSeconds(5)).until(() -> load.status().inFlight() > 10);

            load.stop();

            var status = load.status();
            assertThat(load.isRunning()).isFalse();
            assertThat(status.failed()).isZero();
            assertThat(status.unknown()).isZero();
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(gateway.requests.get()).isEqualTo((int) status.requested());
            assertJournalMatches(status);
            Thread.sleep(200);
            assertThat(gateway.requests.get()).as("nothing is sent after the stop").isEqualTo((int) status.requested());
        }
    }

    @Test
    void requests_still_open_after_the_grace_period_are_cancelled_and_stay_unknown() throws Exception {
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofSeconds(5)).start()) {
            var load = load(TestData.settings(gateway.url()).tps(100), Duration.ofMillis(200));
            load.begin();
            await().atMost(Duration.ofSeconds(5)).until(() -> load.status().inFlight() > 10);

            var stopStarted = System.nanoTime();
            load.stop();

            // The answers would take five seconds: the stop ends at the grace, not when they come.
            assertThat(Duration.ofNanos(System.nanoTime() - stopStarted)).isBetween(Duration.ofMillis(200), Duration.ofSeconds(2));
            var status = load.status();
            assertThat(status.failed()).isZero();
            assertThat(status.unknown()).isGreaterThan(10);
            assertThat(responses(ResponseKind.CANCELLED)).isEqualTo(status.unknown());
            assertJournalMatches(status);
        }
    }

    @Test
    @DisplayName("no retries after a stop, and an unanswered 5xx stays unknown")
    void no_retries_after_a_stop_and_an_unanswered_5xx_stays_unknown() throws Exception {
        try (var gateway = FakeGateway.with().failFirstAttempt().start()) {
            var load = load(TestData.settings(gateway.url()).tps(50).retryBackoff(Duration.ofSeconds(1)));
            load.begin();
            await().atMost(Duration.ofSeconds(5)).until(() -> load.status().requested() > 10);

            load.stop();

            var status = load.status();
            assertThat(gateway.attempts()).containsOnly(1);
            assertThat(status.unknown()).isEqualTo(status.requested());
            assertThat(status.failed()).isZero();
            assertJournalMatches(status);
        }
    }

    @Test
    void a_ledger_rejection_keeps_its_reason_in_the_journal_and_in_the_status() throws Exception {
        try (var gateway = FakeGateway.with().rejectReason(RejectReason.INSUFFICIENT_FUNDS).start()) {
            var load = load(TestData.settings(gateway.url()).tps(20).waitShare(1.0));
            runFor(load, Duration.ofSeconds(1));

            var status = load.status();
            assertThat(status.requested()).isGreaterThan(0);
            assertThat(status.rejected()).isEqualTo(status.requested());
            assertThat(status.rejectedByReason()).containsExactly(entry(RejectReason.INSUFFICIENT_FUNDS, status.requested()));
            assertJournalMatches(status);
            assertThat(journal.outcomes.values()).allSatisfy(it -> {
                assertThat(it.outcome()).isEqualTo(JournalOutcome.ACKNOWLEDGED);
                assertThat(it.bankStatus()).isEqualTo(TransferStatus.REJECTED);
                assertThat(it.rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);
            });
        }
    }

    @Test
    @DisplayName("a duplicate is a second send of the same transfer, not a second transfer")
    void a_duplicate_is_a_second_send_of_the_same_transfer_not_a_second_transfer() throws Exception {
        try (var gateway = new FakeGateway()) {
            var load = load(TestData.settings(gateway.url()).tps(50).waitShare(1.0).duplicates(Duration.ZERO, Duration.ofMillis(50)));
            runFor(load, Duration.ofSeconds(1));

            var status = load.status();
            assertThat(status.requested()).isBetween(35L, 60L);
            assertThat(status.duplicatesSent()).isEqualTo(sentTimes(gateway, 2)).isGreaterThan(status.requested() / 2);
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(status.retries()).isZero();
            assertThat(gateway.attemptsById).hasSize((int) status.requested());
            assertThat(gateway.attempts()).isSubsetOf(1, 2);
            assertJournalMatches(status);
            assertThat(sends("first")).isEqualTo(status.requested());
            assertThat(sends("duplicate")).isEqualTo(status.duplicatesSent());
            assertThat(registry.get("generator.submission.latency").tag("kind", "duplicate").timer().count()).isEqualTo(status.duplicatesSent());
        }
    }

    @Test
    void an_answer_to_either_send_acknowledges_the_transfer() throws Exception {
        // Whichever send of a transfer arrives first gets the 503; with a single attempt alone it would stay unknown.
        try (var gateway = FakeGateway.with().failFirstAttempt().start()) {
            var load = load(TestData.settings(gateway.url()).tps(20).maxAttempts(1).duplicates(Duration.ZERO));
            runFor(load, Duration.ofSeconds(1));

            var status = load.status();
            assertThat(status.requested()).isGreaterThan(0);
            assertThat(status.acknowledged()).isEqualTo(sentTimes(gateway, 2)).isGreaterThan(status.requested() / 2);
            assertThat(status.unknown()).as("the stop came before their duplicate").isEqualTo(sentTimes(gateway, 1));
            assertJournalMatches(status);
        }
    }

    @Test
    void a_stop_cancels_duplicates_that_are_still_waiting_for_their_moment() {
        try (var gateway = new FakeGateway()) {
            var load = load(TestData.settings(gateway.url()).tps(50).duplicates(Duration.ofSeconds(20)));
            load.begin();
            await().atMost(Duration.ofSeconds(5)).until(() -> load.status().requested() > 10);

            var stopStarted = System.nanoTime();
            load.stop();

            assertThat(Duration.ofNanos(System.nanoTime() - stopStarted)).isLessThan(Duration.ofSeconds(3));
            var status = load.status();
            assertThat(status.duplicatesSent()).isZero();
            assertThat(gateway.attempts()).containsOnly(1);
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertJournalMatches(status);
        }
    }

    @Test
    void a_duplicate_cut_off_by_a_stop_does_not_undo_the_answer_the_first_send_got() {
        try (var gateway = FakeGateway.with().repeatDelay(Duration.ofSeconds(5)).start()) {
            var load = load(TestData.settings(gateway.url()).tps(50).duplicates(Duration.ofMillis(50)), Duration.ofMillis(300));
            load.begin();
            await().atMost(Duration.ofSeconds(5)).until(() -> load.status().duplicatesSent() > 5);

            load.stop();

            var status = load.status();
            assertThat(status.unknown()).isZero();
            assertThat(status.acknowledged()).isEqualTo(status.requested());
            assertThat(responses(ResponseKind.CANCELLED)).isGreaterThan(0);
            assertJournalMatches(status);
        }
    }

    @Test
    void a_second_stop_changes_nothing() {
        try (var gateway = FakeGateway.with().responseDelay(Duration.ofMillis(300)).start()) {
            var load = load(TestData.settings(gateway.url()).tps(100));
            load.begin();
            await().atMost(Duration.ofSeconds(5)).until(() -> load.status().inFlight() > 10);

            load.stop();
            var stopped = load.status();
            load.stop();

            assertThat(load.status()).isEqualTo(stopped);
            assertJournalMatches(stopped);
        }
    }
}
