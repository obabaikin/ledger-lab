package com.ledgerlab.control.metrics;

import java.time.Duration;

/** The chart queries take the window of their rates as {@code %s}, in Prometheus' form ({@code 15s}, {@code 1m}). */
public final class BankMetrics {

    public static final String ASSIGNED_PARTITIONS = "ledger_kafka_assigned_partitions";
    /** Set by the chart's Prometheus config on the project's own services, not on the exporters beside them. */
    public static final String UP = "up{lab_service=\"true\"}";
    public static final String ACCEPTED_RATE = "sum(rate(gateway_transfers_total{outcome=\"accepted\"}[%s]))";
    public static final String BUSY_RATE = "sum(rate(gateway_transfers_total{outcome=\"refused\"}[%s]))";
    public static final String POSTED_RATE = "sum(rate(ledger_transfers_total{outcome=\"posted\"}[%s]))";
    public static final String REJECTED_RATE = "sum(rate(ledger_transfers_total{outcome=\"rejected\"}[%s]))";
    public static final String LATENCY_P50_MS = latencyQuantileMs("0.5");
    public static final String LATENCY_P99_MS = latencyQuantileMs("0.99");

    /** The gateway's transfers whose result came in the minute before the instant asked about. */
    public static final String TRANSFERS_IN_MINUTE = "sum(increase(gateway_transfer_latency_seconds_count[1m]))";
    /** Of them, those not slower than a bound, given as {@code %s} by {@link #bucketBound}. */
    public static final String FAST_IN_MINUTE = "sum(increase(gateway_transfer_latency_seconds_bucket{le=\"%s\"}[1m]))";
    /**
     * Above 0 if a process of the project's services started again under the same pod in the minute before, or up to
     * four minutes earlier: the 5 minutes span the restart's gap without samples. A pod replaced by a new one is another
     * series, whose start time never changes.
     */
    public static final String RESTARTED_IN_MINUTE = "sum(max_over_time(process_start_time_seconds{lab_service=\"true\"}[1m])"
        + " > bool min_over_time(process_start_time_seconds{lab_service=\"true\"}[5m]))";

    private BankMetrics() {
    }

    private static String latencyQuantileMs(String quantile) {
        return "1000 * histogram_quantile(" + quantile + ", sum by (le) (rate(gateway_transfer_latency_seconds_bucket[%s])))";
    }

    /** As Prometheus stores a histogram's bucket bound: seconds with at least one decimal, 2 s as {@code 2.0}. */
    public static String bucketBound(Duration bound) {
        return Double.toString(bound.toNanos() / 1e9);
    }

    /** As PromQL writes a duration: whole seconds, or whole minutes where they fit. */
    public static String window(Duration window) {
        var seconds = window.toSeconds();
        return seconds % 60 == 0 ? seconds / 60 + "m" : seconds + "s";
    }
}
