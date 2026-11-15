package com.ledgerlab.generator;

import java.time.Duration;
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
    ProfileProperties profile
) {
    /**
     * {@link #tps} new transfers a second, all the time. A pulse may raise that to {@link #maxPulseTps} for at most
     * {@link #maxPulse}. {@link #waitShare} of the clients wait up to {@link #waitTimeout} for the final status.
     */
    public record Load(
        boolean enabled,
        int tps,
        int maxPulseTps,
        Duration maxPulse,
        double waitShare,
        Duration waitTimeout,
        // How long the answers counted by minute are kept for whoever measures availability.
        Duration minutesRetention
    ) {
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
