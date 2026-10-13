package com.ledgerlab.control.generator

import java.time.Instant
import java.util.UUID

/** Control's model of the generator's internal API, checked against generator/openapi.json. */
object GeneratorApi {
    const val SIMULATIONS_PATH = "/internal/simulations"
    const val SIMULATION_PATH = "/internal/simulations/{runId}"
    const val STOP_PATH = "/internal/simulations/{runId}/stop"
    const val RECONCILIATION_PATH = "/internal/simulations/{runId}/reconciliation"
}

data class StartSimulationRequest(
    val runId: UUID,
    val tps: Int,
    val durationSeconds: Int,
    val accountCount: Int,
    val blockedAccounts: Int,
)

enum class SimulationState(val active: Boolean) {
    RUNNING(active = true),
    STOPPING(active = true),
    FINISHED(active = false),
    STOPPED(active = false),
}

data class SimulationStatus(
    val runId: UUID,
    val state: SimulationState,
    val finishedAt: Instant?,
    val requested: Long,
    val acknowledged: Long,
    val failed: Long,
    val unknown: Long,
    val retries: Long,
    val duplicatesSent: Long,
    val waitedLatencyP50Ms: Double?,
    val waitedLatencyP99Ms: Double?,
)

data class TransferIds(val count: Long, val transferIds: List<UUID>)

data class ReconciliationBreaks(
    val notInJournal: TransferIds,
    val acknowledgedNotInStatement: TransferIds,
    val failedButInStatement: TransferIds,
    val payloadMismatch: TransferIds,
    val statusMismatch: TransferIds,
)

data class UnknownResolved(val accepted: TransferIds, val notAccepted: TransferIds)

data class JournalTotals(
    val requested: Long,
    val acknowledged: Long,
    val failed: Long,
    val unknown: Long,
    val acknowledgedAmountCents: Long,
)

data class StatementTotals(val rows: Long, val amountCents: Long, val byStatus: Map<String, Long>)

data class ClientReconciliationReport(
    val runId: UUID,
    val balanced: Boolean,
    val statementFinal: Boolean,
    val journal: JournalTotals,
    val statement: StatementTotals,
    val breaks: ReconciliationBreaks,
    val unknownResolved: UnknownResolved,
)
