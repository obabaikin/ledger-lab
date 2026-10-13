package com.ledgerlab.control.reporting

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** Control's model of the reporting service's internal API, checked against reporting/openapi.json. */
object ReportingApi {
    const val STATS_PATH = "/internal/runs/{runId}/stats"
    const val RUN_PATH = "/internal/runs/{runId}"
}

@Schema(description = "A run's numbers from the bank's events, as the reporting service had them at `at`; they trail the books while events are on the way.")
data class RunStats(
    val runId: UUID,
    @field:Schema(description = "Time of the snapshot, by the reporting database's clock.")
    val at: Instant,
    val totals: StatsTotals,
    val bankLatency: BankLatency,
    @field:Schema(description = "Increments per second, oldest first.")
    val series: List<SecondStats>,
)

data class StatsTotals(
    @field:Schema(description = "Transfers the gateway accepted, including those known only by their result so far.")
    val accepted: Long,
    @field:Schema(description = "Transfers whose command is seen: their amount and acceptedAt are known.")
    val commandsSeen: Long,
    val posted: Long,
    val rejected: Long,
    @field:Schema(description = "accepted − posted − rejected.")
    val inFlight: Long,
    @field:Schema(description = "Sum of posted transfers whose amount is known.")
    val postedAmountCents: Long,
    val rejectedByReason: Map<String, Long>,
)

@Schema(description = "processedAt − acceptedAt (ledger clock minus gateway clock); null without both.")
data class BankLatency(val p50Ms: Double?, val p99Ms: Double?)

data class SecondStats(
    @field:Schema(description = "Start of the second.")
    val second: Instant,
    @field:Schema(description = "By acceptedAt, or by processedAt while the command is not seen yet.")
    val accepted: Long,
    @field:Schema(description = "By processedAt, as the rest.")
    val posted: Long,
    val rejected: Long,
    val postedAmountCents: Long,
    val bankP50Ms: Double?,
    val bankP99Ms: Double?,
)
