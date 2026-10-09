package com.ledgerlab.common

object Observability {
    /** MDC key that every service puts on log lines belonging to a run. */
    const val MDC_RUN_ID = "runId"

    /** Percentiles published for every latency timer, so dashboards can compare services. */
    val LATENCY_PERCENTILES = doubleArrayOf(0.5, 0.95, 0.99)
}
