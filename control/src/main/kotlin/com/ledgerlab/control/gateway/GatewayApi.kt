package com.ledgerlab.control.gateway

import java.time.Instant
import java.util.UUID

/** Control's model of the gateway's internal API, checked against gateway/openapi-internal.json. */
object GatewayApi {
    const val SUMMARY_PATH = "/internal/runs/{runId}/summary"
    const val CLOSE_PATH = "/internal/runs/{runId}/close"
    const val RUN_PATH = "/internal/runs/{runId}"
}

data class RunSummary(
    val runId: UUID,
    val accepted: Long,
    val published: Long,
    val posted: Long,
    val rejected: Long,
    val rejectionsByReason: Map<String, Long>,
    val latencyP50Ms: Double?,
    val latencyP99Ms: Double?,
)

data class ClosedPeriod(val runId: UUID, val closedAt: Instant)
