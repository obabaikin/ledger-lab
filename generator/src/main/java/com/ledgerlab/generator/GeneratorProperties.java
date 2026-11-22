package com.ledgerlab.generator;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("generator")
public record GeneratorProperties(
    String gatewayUrl,
    Duration connectTimeout,
    Duration requestTimeout,
    // Attempts per transfer, the first one included; past that the outcome stays unknown (ADR 0006).
    int maxAttempts,
    // Pause before the second attempt; it doubles for each further one, with jitter.
    Duration retryBackoff,
    // Requests open at once; a transfer due beyond that is skipped and counted, not queued.
    int maxInFlight,
    Load load,
    Accounts accounts,
    Journal journal,
    Periods periods,
    Reconciliation reconciliation,
    Totals totals,
    Lease lease,
    ProfileProperties profile
) {
    /**
     * The base load: {@link #tps} new transfers a second all the time, or, with the {@link #profile} on, the profile's
     * value at the moment. A pulse may raise that to {@link #maxPulseTps} for at most {@link #maxPulse}.
     * {@link #waitShare} of the clients wait up to {@link #waitTimeout} for the final status.
     */
    public record Load(
        boolean enabled,
        int tps,
        int maxPulseTps,
        Duration maxPulse,
        double waitShare,
        Duration waitTimeout,
        // How long the answers counted by minute are kept for whoever measures availability.
        Duration minutesRetention,
        BaseProfile profile
    ) {
    }

    /**
     * A day of the base load in {@link #timeZone}: {@link #nightTps} from {@link #nightStart} to {@link #nightEnd}; after that a random
     * walk of {@link #steps} every {@link #stepMinutes} between {@link #dayMinTps} and {@link #dayMaxTps}, back at the night's value when
     * the night starts. {@link #seed} with the date fixes the walk of each day.
     */
    public record BaseProfile(
        boolean enabled,
        long seed,
        int nightTps,
        int dayMinTps,
        int dayMaxTps,
        List<Integer> steps,
        int stepMinutes,
        LocalTime nightStart,
        LocalTime nightEnd,
        ZoneId timeZone
    ) {
        private static final int MINUTES_PER_HOUR = 60;

        public BaseProfile {
            steps = List.copyOf(steps);
            if (enabled) {
                validate(nightTps, dayMinTps, dayMaxTps, steps, stepMinutes, nightStart, nightEnd);
            }
        }

        private static void validate(
            int nightTps, int dayMinTps, int dayMaxTps, List<Integer> steps, int stepMinutes, LocalTime nightStart, LocalTime nightEnd
        ) {
            if (nightTps < 1 || nightTps > dayMinTps || dayMinTps > dayMaxTps) {
                throw new IllegalArgumentException("generator.load.profile needs 1 <= night-tps <= day-min-tps <= day-max-tps");
            }
            if (steps.isEmpty() || steps.stream().anyMatch(step -> step < 1)) {
                throw new IllegalArgumentException("generator.load.profile.steps must be positive, at least one");
            }
            var smallest = Collections.min(steps);
            if (steps.stream().anyMatch(step -> step % smallest != 0)
                || (dayMinTps - nightTps) % smallest != 0 || (dayMaxTps - dayMinTps) % smallest != 0) {
                throw new IllegalArgumentException("generator.load.profile: the steps and the tps limits must be multiples of the smallest step");
            }
            if (stepMinutes < 1 || MINUTES_PER_HOUR % stepMinutes != 0) {
                throw new IllegalArgumentException("generator.load.profile.step-minutes must divide an hour");
            }
            if (nightStart.equals(nightEnd) || !onStep(nightStart, stepMinutes) || !onStep(nightEnd, stepMinutes)) {
                throw new IllegalArgumentException("generator.load.profile: the night must start and end on different step boundaries");
            }
        }

        private static boolean onStep(LocalTime time, int stepMinutes) {
            return time.getSecond() == 0 && time.getNano() == 0 && time.getMinute() % stepMinutes == 0;
        }
    }

    /** Account numbers 1..{@link #hot} receive the hot share; the last {@link #blocked} numbers are blocked (the ledger's convention). */
    public record Accounts(int count, int blocked, int hot) {
    }

    /** One writer commits up to {@link #batchSize} rows at once, {@link #flushInterval} after the first of them came. */
    public record Journal(int batchSize, Duration flushInterval) {
    }

    /** The journal is cut by the bank's periods and held for {@link #retention} after a period ended (ADR 0022). */
    public record Periods(
        Duration length, Duration retention, Duration partitionsAhead, Duration maintenanceInterval, Duration lockTimeout, int unpartitionedDeleteBatch
    ) {
        /** Partitions are named to the minute of their period's start. */
        public Periods {
            if (length.compareTo(Duration.ofMinutes(1)) < 0 || !length.equals(Duration.ofMinutes(length.toMinutes()))) {
                throw new IllegalArgumentException("generator.periods.length must be whole minutes, at least one");
            }
        }
    }

    /** The clients' totals are read every {@link #readInterval} and their increments folded every {@link #rollupInterval}. */
    public record Totals(Duration readInterval, Duration rollupInterval) {
    }

    /**
     * One instance sends at a time. {@link #owner} names this instance in the lease, which lasts {@link #duration}; the holder
     * renews it every {@link #renewEvery}, the others try to take it every {@link #acquireEvery}. The holder sends only until
     * {@link #sendWindow} after it sent its last successful renewal: shorter than the lease, so a holder cut off from the
     * database is silent before another may take over.
     */
    public record Lease(String owner, Duration duration, Duration renewEvery, Duration acquireEvery, Duration sendWindow) {
        public Lease {
            if (owner.isBlank()) {
                throw new IllegalArgumentException("generator.lease.owner must not be blank");
            }
            if (sendWindow.compareTo(duration) >= 0) {
                throw new IllegalArgumentException("generator.lease.send-window must be shorter than generator.lease.duration");
            }
            if (renewEvery.compareTo(sendWindow) >= 0) {
                throw new IllegalArgumentException("generator.lease.renew-every must be shorter than generator.lease.send-window");
            }
        }
    }

    /**
     * A report lists at most {@link #exampleLimit} transfers per kind of break; {@link #resolveConcurrency} unknown transfers are asked about at once.
     * {@link #workMem} is Postgres's work_mem for the comparison's hash joins.
     */
    public record Reconciliation(Duration statementTimeout, DataSize workMem, int exampleLimit, int resolveConcurrency) {
        /** Postgres refuses less, and only when the first reconciliation runs, an hour after the start. */
        public static final DataSize MIN_WORK_MEM = DataSize.ofKilobytes(64);

        public Reconciliation {
            if (workMem.compareTo(MIN_WORK_MEM) < 0) {
                throw new IllegalArgumentException("generator.reconciliation.work-mem must be at least " + MIN_WORK_MEM);
            }
        }
    }
}
