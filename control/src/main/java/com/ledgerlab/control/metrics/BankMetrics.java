package com.ledgerlab.control.metrics;

public final class BankMetrics {

    public static final String ASSIGNED_PARTITIONS = "ledger_kafka_assigned_partitions";
    /** Set by the chart's Prometheus config on the project's own services, not on the exporters beside them. */
    public static final String UP = "up{lab_service=\"true\"}";
    public static final String ACCEPTED_RATE = "sum(rate(gateway_transfers_total{outcome=\"accepted\"}[1m]))";
    public static final String BUSY_RATE = "sum(rate(gateway_transfers_total{outcome=\"refused\"}[1m]))";
    public static final String POSTED_RATE = "sum(rate(ledger_transfers_total{outcome=\"posted\"}[1m]))";
    public static final String REJECTED_RATE = "sum(rate(ledger_transfers_total{outcome=\"rejected\"}[1m]))";
    public static final String LATENCY_P50_MS = "1000 * max(reporting_bank_latency_seconds{quantile=\"0.5\"})";
    public static final String LATENCY_P99_MS = "1000 * max(reporting_bank_latency_seconds{quantile=\"0.99\"})";

    private BankMetrics() {
    }
}
