package com.ledgerlab.reporting.domain

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "A run's transfers as seen in the bank's events; one consistent snapshot, lagging the books while events are on the way.")
data class RunStats(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val runId: UUID,
    @field:Schema(description = "Time of the snapshot, by the reporting database's clock.", requiredMode = Schema.RequiredMode.REQUIRED)
    val at: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val totals: StatsTotals,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val bankLatency: BankLatency,
    @field:Schema(description = "Increments per second, oldest first.", requiredMode = Schema.RequiredMode.REQUIRED)
    val series: List<SecondStats>,
)

data class StatsTotals(
    @field:Schema(description = "Transfers the gateway accepted, including those known only by their result so far.", requiredMode = Schema.RequiredMode.REQUIRED)
    val accepted: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val posted: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val rejected: Long,
    @field:Schema(description = "accepted − posted − rejected.", requiredMode = Schema.RequiredMode.REQUIRED)
    val inFlight: Long,
    @field:Schema(description = "Sum of posted transfers whose amount is known.", requiredMode = Schema.RequiredMode.REQUIRED)
    val postedAmountCents: Long,
    @field:Schema(description = "Rejected transfers by reason; a reason this service does not know is kept as sent.", requiredMode = Schema.RequiredMode.REQUIRED)
    val rejectedByReason: Map<String, Long>,
)

@Schema(description = "processedAt − acceptedAt over transfers with both known; null when there are none.")
data class BankLatency(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    val p50Ms: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    val p99Ms: Double?,
)

data class SecondStats(
    @field:Schema(description = "Start of the second.", requiredMode = Schema.RequiredMode.REQUIRED)
    val second: Instant,
    @field:Schema(description = "By acceptedAt, or by processedAt while the command is not seen yet.", requiredMode = Schema.RequiredMode.REQUIRED)
    val accepted: Long,
    @field:Schema(description = "By processedAt.", requiredMode = Schema.RequiredMode.REQUIRED)
    val posted: Long,
    @field:Schema(description = "By processedAt.", requiredMode = Schema.RequiredMode.REQUIRED)
    val rejected: Long,
    @field:Schema(description = "By processedAt.", requiredMode = Schema.RequiredMode.REQUIRED)
    val postedAmountCents: Long,
    @field:Schema(description = "Bank latency of transfers processed in this second; null when none has a known acceptedAt.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    val bankP50Ms: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    val bankP99Ms: Double?,
)
