package com.ledgerlab.common

object Observability {
    const val MDC_RUN_ID = "runId"

    /** Percentiles published for every latency timer, so dashboards can compare services. */
    val LATENCY_PERCENTILES = doubleArrayOf(0.5, 0.95, 0.99)
}
