package com.ledgerlab.common;

public final class Observability {
    public static final String MDC_RUN_ID = "runId";

    private static final double[] LATENCY_PERCENTILES = {0.5, 0.95, 0.99};

    private Observability() {
    }

    /** Percentiles published for every latency timer, so dashboards can compare services. */
    public static double[] latencyPercentiles() {
        return LATENCY_PERCENTILES.clone();
    }
}
