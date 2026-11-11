package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The bank's clients, sending all the time (ADR 0021). A pacer launches the target number of transfers a second,
 * spread evenly; each is a virtual thread over a blocking HTTP call, so slow answers do not slow the pace. A transfer
 * is written to the client journal before its request leaves.
 */
@Component
public class Load implements SmartLifecycle {

    public static final String TRANSFERS_METRIC = "generator.transfers";
    public static final String OUTCOME_TAG = "outcome";
    public static final String RESPONSES_METRIC = "generator.responses";
    public static final String KIND_TAG = "kind";
    public static final String SKIPPED_METRIC = "generator.load.skipped";
    public static final String NOT_JOURNALED_METRIC = "generator.load.not-journaled";

    private static final Duration PACER_TICK = Duration.ofMillis(1);
    /** A send that ended without an answer, possibly after the request went out: it cannot count as failed. */
    private static final SubmissionResult CUT_SHORT =
        new SubmissionResult(null, null, null, GatewayClient.FIRST_ATTEMPT, SubmissionOutcome.UNKNOWN, Duration.ZERO, 0);
    private static final Duration DRAIN_POLL = Duration.ofMillis(10);
    /** Time for a request that has just timed out to finish its thread. */
    private static final Duration STOP_GRACE_MARGIN = Duration.ofSeconds(1);
    private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    /** Same percentiles as the bank's services publish, so dashboards can put them side by side. */
    private static final double[] LATENCY_PERCENTILES = {0.5, 0.95, 0.99};

    private static final Logger log = LoggerFactory.getLogger(Load.class);

    private final GeneratorProperties.Load props;
    private final Duration stopGrace;
    private final LoadProfile profile;
    private final GatewayClient client;
    private final ClientJournal journal;
    private final ResponseMinutes minutes;
    private final Clock clock;
    private final double duplicateShare;
    private final List<Duration> duplicateDelays;

    // Submissions are not started by the pacer's own thread pool, so stopping the pacer leaves open requests running.
    private final ExecutorService submissions = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight;
    private final CopyOnWriteArrayList<ActivePulse> pulses = new CopyOnWriteArrayList<>();

    private final LongAdder requested = new LongAdder();
    private final LongAdder acknowledged = new LongAdder();
    private final LongAdder posted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final ConcurrentHashMap<RejectReason, LongAdder> rejectedByReason = new ConcurrentHashMap<>();
    private final LongAdder failed = new LongAdder();
    private final LongAdder unknown = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder refused = new LongAdder();
    private final LongAdder duplicatesSent = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder notJournaled = new LongAdder();
    private final AtomicLong open = new AtomicLong();
    private final Map<ResponseKind, Counter> responses = new EnumMap<>(ResponseKind.class);
    /** How long a user waited for a final answer to one press, retries and their pauses included. */
    private final Map<SubmissionKind, Timer> submissionLatency = new EnumMap<>(SubmissionKind.class);
    /** One point per answered send; a histogram, so percentiles can be taken over any mix of tags. */
    private final Map<SendTimerKey, Timer> sendLatency = new HashMap<>();

    private volatile @Nullable Instant startedAt = null;
    private volatile boolean running = false;
    /** Opens when the load stops, for a duplicate that waits for its moment. */
    private final CountDownLatch noLongerRunning = new CountDownLatch(1);
    private volatile @Nullable Thread pacer = null;

    private record SendTimerKey(boolean waited, SubmissionKind kind, boolean isFinal) {
    }

    private record Answer(SubmissionResult result, long endedNanos) {
    }

    private record ActivePulse(int tps, long untilNanos, Instant until) {
    }

    @Autowired
    public Load(GeneratorProperties props, GatewayClient client, ClientJournal journal, ResponseMinutes minutes, Clock clock, MeterRegistry registry) {
        // The slowest open request ends by its own timeouts: connecting, then the wait plus the request timeout.
        // No retry starts after a stop, so backoffs only need the margin.
        this(
            props, client, journal, minutes, clock, registry,
            props.connectTimeout().plus(props.load().waitTimeout()).plus(props.requestTimeout()).plus(STOP_GRACE_MARGIN));
    }

    Load(
        GeneratorProperties props,
        GatewayClient client,
        ClientJournal journal,
        ResponseMinutes minutes,
        Clock clock,
        MeterRegistry registry,
        Duration stopGrace
    ) {
        var duplicates = props.profile();
        if (duplicates.duplicateShare() != 0.0 && duplicates.duplicateDelays().isEmpty()) {
            throw new IllegalArgumentException("duplicates need at least one delay");
        }
        this.props = props.load();
        this.stopGrace = stopGrace;
        this.profile = new LoadProfile(
            props.accounts().count(), props.accounts().blocked(), props.accounts().hot(),
            duplicates.oversizedShare(), duplicates.blockedShare());
        this.client = client;
        this.journal = journal;
        this.minutes = minutes;
        this.clock = clock;
        this.duplicateShare = duplicates.duplicateShare();
        this.duplicateDelays = duplicates.duplicateDelays();
        this.inFlight = new Semaphore(props.maxInFlight());
        Map.of(SubmissionOutcome.ACKNOWLEDGED, acknowledged, SubmissionOutcome.FAILED, failed, SubmissionOutcome.UNKNOWN, unknown)
            .forEach((outcome, count) -> FunctionCounter.builder(TRANSFERS_METRIC, count, LongAdder::sum)
                .description("Transfers of the clients by what the client learned; unknown: it gave up without knowing")
                .tag(OUTCOME_TAG, outcome.tag())
                .register(registry));
        FunctionCounter.builder(SKIPPED_METRIC, skipped, LongAdder::sum)
            .description("Transfers that were due while as many requests as allowed were open; never sent")
            .register(registry);
        FunctionCounter.builder(NOT_JOURNALED_METRIC, notJournaled, LongAdder::sum)
            .description("Transfers not sent because the client journal could not store them first")
            .register(registry);
        for (var kind : ResponseKind.values()) {
            responses.put(kind, Counter.builder(RESPONSES_METRIC)
                .description("Requests to the bank by how they ended, retries and duplicates included")
                .tag(KIND_TAG, kind.tag())
                .register(registry));
        }
        for (var kind : List.of(SubmissionKind.FIRST, SubmissionKind.DUPLICATE)) {
            submissionLatency.put(kind, Timer.builder("generator.submission.latency").tag("waited", "true").tag("kind", kind.tag())
                .publishPercentiles(LATENCY_PERCENTILES).register(registry));
        }
        for (var waited : List.of(true, false)) {
            for (var kind : SubmissionKind.values()) {
                for (var isFinal : List.of(true, false)) {
                    sendLatency.put(
                        new SendTimerKey(waited, kind, isFinal),
                        Timer.builder("generator.send.latency")
                            .tag("waited", Boolean.toString(waited)).tag("kind", kind.tag()).tag("final", Boolean.toString(isFinal))
                            .publishPercentileHistogram().register(registry));
                }
            }
        }
    }

    public Duration stopGrace() {
        return stopGrace;
    }

    @Override
    public void start() {
        if (props.enabled()) {
            begin();
        }
    }

    /** The clock of the pace starts here, not at construction, so the first tick has nothing to catch up. */
    public synchronized void begin() {
        if (running || startedAt != null) {
            return;
        }
        startedAt = clock.instant();
        running = true;
        pacer = Thread.ofVirtual().name("pacer").start(this::pace);
    }

    private void pace() {
        var last = System.nanoTime();
        var due = 0.0;
        try {
            while (running) {
                Thread.sleep(PACER_TICK);
                var now = System.nanoTime();
                var tps = targetTps(now);
                // After a stall the pacer does not send all it missed at once: at most a second's worth.
                due = Math.min(due + tps * (now - last) / NANOS_PER_SECOND, tps);
                last = now;
                for (; due >= 1.0; due -= 1.0) {
                    // A stop must not wait for the pacer to launch what is due.
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                    if (inFlight.tryAcquire()) {
                        launch();
                    } else {
                        skipped.increment();
                    }
                }
            }
        } catch (InterruptedException e) {
            // A stop interrupts the pacer and takes the load from here.
            Thread.currentThread().interrupt();
        }
    }

    private void launch() {
        open.incrementAndGet();
        try {
            // The task always starts, so its finally always runs, also for a submission launched just before a stop.
            submissions.execute(() -> {
                try {
                    submitOne();
                } finally {
                    open.decrementAndGet();
                    inFlight.release();
                }
            });
        } catch (RuntimeException e) {
            open.decrementAndGet();
            inFlight.release();
            throw e;
        }
    }

    /**
     * Raises the load to {@code tps} for {@code length} from now. Pulses do not add up: while several are in effect,
     * the highest of them and the base sets the pace.
     */
    public void pulse(int tps, Duration length) {
        if (tps < 1 || tps > props.maxPulseTps()) {
            throw new InvalidPulseException("tps must be between 1 and " + props.maxPulseTps());
        }
        if (length.isNegative() || length.isZero() || length.compareTo(props.maxPulse()) > 0) {
            throw new InvalidPulseException("a pulse lasts at most " + props.maxPulse());
        }
        var now = System.nanoTime();
        pulses.removeIf(pulse -> pulse.untilNanos() - now <= 0);
        pulses.add(new ActivePulse(tps, now + length.toNanos(), clock.instant().plus(length)));
    }

    private @Nullable ActivePulse highestPulse(long nowNanos) {
        return pulses.stream()
            .filter(pulse -> pulse.untilNanos() - nowNanos > 0)
            .max(Comparator.comparingInt(ActivePulse::tps))
            .orElse(null);
    }

    private int targetTps(long nowNanos) {
        var pulse = highestPulse(nowNanos);
        return pulse == null ? props.tps() : Math.max(props.tps(), pulse.tps());
    }

    /**
     * Stops the clients: no new transfers, no retries. Requests already sent get {@link #stopGrace()} to receive
     * their answer; whatever is left after that is cancelled and stays unknown.
     */
    @Override
    public void stop() {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
        }
        noLongerRunning.countDown();
        try {
            // Until the pacer has finished, it may still be counting a submission it was launching.
            var pacing = pacer;
            if (pacing != null) {
                pacing.interrupt();
                pacing.join();
            }
            var deadline = System.nanoTime() + stopGrace.toNanos();
            while (open.get() > 0 && System.nanoTime() - deadline < 0) {
                Thread.sleep(DRAIN_POLL);
            }
            if (open.get() > 0) {
                submissions.shutdownNow();
                while (!submissions.awaitTermination(stopGrace.toNanos() + (long) NANOS_PER_SECOND, TimeUnit.NANOSECONDS)) {
                    log.warn("The load still has {} submissions that do not end", open.get());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info(
            "The load stopped: requested={} acknowledged={} failed={} unknown={}",
            requested.sum(), acknowledged.sum(), failed.sum(), unknown.sum());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return JournalWriter.PHASE + 1;
    }

    private void submitOne() {
        // A submission launched just before a stop may start after it; it must not send.
        if (!running) {
            return;
        }
        var transfer = profile.next();
        // As the journal's column holds it, so the outcome finds its intent by the same time.
        var sentAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            journal.intent(sentAt, transfer);
        } catch (JournalUnavailableException e) {
            notJournaled.increment();
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // Counted only once the intent exists, so that every request ends up in exactly one outcome.
        requested.increment();
        var random = ThreadLocalRandom.current();
        var wait = random.nextDouble() < props.waitShare() ? props.waitTimeout() : null;
        var duplicateDelay = random.nextDouble() < duplicateShare ? duplicateDelays.get(random.nextInt(duplicateDelays.size())) : null;
        // Collected as each send ends, so a 2xx already received still counts when the other send is cut short.
        var answers = new ConcurrentLinkedQueue<Answer>();
        var duplicate = duplicateDelay == null ? null : Thread.ofVirtual().start(() -> {
            var answer = sendDuplicate(transfer, wait, duplicateDelay);
            if (answer != null) {
                answers.add(answer);
            }
        });
        var cutShort = false;
        var interrupted = false;
        try {
            var main = client.submit(transfer, wait, () -> running, recorder(wait != null, false));
            answers.add(new Answer(main, System.nanoTime()));
            retries.add(main.attempts() - GatewayClient.FIRST_ATTEMPT);
            recordSubmissionLatency(main, wait, SubmissionKind.FIRST);
        } catch (InterruptedException e) {
            cutShort = true;
            interrupted = true;
        } catch (RuntimeException e) {
            cutShort = true;
            log.warn("A submission ended with an error", e);
        }
        if (duplicate != null) {
            // A first send that did not end well takes its duplicate with it; both have ended before the answers are put together.
            if (cutShort) {
                duplicate.interrupt();
            }
            while (duplicate.isAlive()) {
                try {
                    duplicate.join();
                } catch (InterruptedException e) {
                    cutShort = true;
                    interrupted = true;
                    duplicate.interrupt();
                }
            }
        }
        countRefused(answers);
        var results = inOrder(answers);
        if (cutShort) {
            results.add(CUT_SHORT);
        }
        settle(sentAt, transfer, OutcomeRule.combine(results));
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void countRefused(Collection<Answer> answers) {
        if (answers.stream().anyMatch(it -> it.result().refusals() > 0)) {
            refused.increment();
        }
    }

    private static List<SubmissionResult> inOrder(Collection<Answer> answers) {
        var results = new ArrayList<SubmissionResult>();
        answers.stream().sorted(Comparator.comparingLong(Answer::endedNanos)).forEach(it -> results.add(it.result()));
        return results;
    }

    private @Nullable Answer sendDuplicate(PlannedTransfer transfer, @Nullable Duration wait, Duration delay) {
        try {
            var stopped = noLongerRunning.await(delay.toMillis(), TimeUnit.MILLISECONDS);
            if (stopped || !running) {
                return null;
            }
            duplicatesSent.increment();
            SubmissionResult result;
            try {
                result = client.submit(transfer, wait, () -> false, recorder(wait != null, true));
            } catch (RuntimeException e) {
                // Kept to this send, so it cannot cancel the first send and lose an answer that one gets.
                log.warn("A duplicate ended with an error", e);
                result = CUT_SHORT;
            }
            recordSubmissionLatency(result, wait, SubmissionKind.DUPLICATE);
            return new Answer(result, System.nanoTime());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private SendRecorder recorder(boolean waited, boolean duplicate) {
        return (attempt, startedNanos, endedNanos, kind, finalStatus) -> {
            Objects.requireNonNull(responses.get(kind), "a counter for every kind of response").increment();
            minutes.record(kind);
            if (kind != ResponseKind.ACCEPTED) {
                return;
            }
            SubmissionKind submission;
            if (duplicate) {
                submission = SubmissionKind.DUPLICATE;
            } else if (attempt == GatewayClient.FIRST_ATTEMPT) {
                submission = SubmissionKind.FIRST;
            } else {
                submission = SubmissionKind.RETRY;
            }
            var timer = sendLatency.get(new SendTimerKey(waited, submission, finalStatus));
            Objects.requireNonNull(timer, "a timer for every kind of send").record(endedNanos - startedNanos, TimeUnit.NANOSECONDS);
        };
    }

    private void recordSubmissionLatency(SubmissionResult result, @Nullable Duration wait, SubmissionKind kind) {
        var status = result.status();
        if (wait != null && result.outcome() == SubmissionOutcome.ACKNOWLEDGED && status != null && status.isFinal()) {
            Objects.requireNonNull(submissionLatency.get(kind), "a timer for first sends and duplicates").record(result.latency());
        }
    }

    private void settle(Instant sentAt, PlannedTransfer transfer, SubmissionResult result) {
        switch (result.outcome()) {
            case FAILED -> {
                failed.increment();
                journal.outcome(sentAt, transfer, JournalOutcome.FAILED, null, null, null);
            }
            // Nothing is written: an intent without an outcome is what unknown means in the journal.
            case UNKNOWN -> unknown.increment();
            case ACKNOWLEDGED -> {
                acknowledged.increment();
                var status = result.status();
                if (status != null) {
                    switch (status) {
                        case POSTED -> posted.increment();
                        case REJECTED -> {
                            rejected.increment();
                            var reason = result.rejectReason();
                            if (reason != null) {
                                rejectedByReason.computeIfAbsent(reason, key -> new LongAdder()).increment();
                            }
                        }
                        case ACCEPTED, PUBLISHED -> {
                        }
                    }
                }
                journal.outcome(sentAt, transfer, JournalOutcome.ACKNOWLEDGED, status, result.rejectReason(), result.acceptedAt());
            }
        }
    }

    public LoadStatus status() {
        var now = System.nanoTime();
        var pulse = highestPulse(now);
        var byReason = new LinkedHashMap<RejectReason, Long>();
        rejectedByReason.forEach((reason, count) -> byReason.put(reason, count.sum()));
        return new LoadStatus(
            startedAt, props.tps(), targetTps(now),
            pulse == null ? null : new LoadStatus.Pulse(pulse.tps(), pulse.until()),
            requested.sum(), acknowledged.sum(), posted.sum(), rejected.sum(), byReason,
            failed.sum(), unknown.sum(), retries.sum(), refused.sum(), duplicatesSent.sum(),
            skipped.sum(), notJournaled.sum(), open.get());
    }
}
