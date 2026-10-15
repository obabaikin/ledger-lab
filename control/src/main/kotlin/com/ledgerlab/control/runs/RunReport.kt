package com.ledgerlab.control.runs

import com.ledgerlab.control.gateway.RunSummary
import com.ledgerlab.control.generator.ClientReconciliationReport
import com.ledgerlab.control.generator.JournalTotals
import com.ledgerlab.control.generator.ReconciliationBreaks
import com.ledgerlab.control.generator.UnknownResolved
import com.ledgerlab.control.ledger.ReconciliationCheck
import com.ledgerlab.control.ledger.ReconciliationReport
import com.ledgerlab.control.reporting.RunStats
import com.ledgerlab.control.chaos.ChaosEvent
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Duration
import java.time.Instant

object RunOutcomes {
    const val SETUP_TIMED_OUT = "setup timed out"
    const val SETUP_REJECTED = "setup rejected"
    const val PERIOD_NOT_CLOSED = "period not closed"
    const val BANK_UNAVAILABLE = "bank unavailable"
    const val CLIENT_LOST = "client lost"
    const val CLIENT_RECONCILIATION_UNAVAILABLE = "client reconciliation unavailable"
    const val STATEMENT_NOT_FINAL = "statement not final"

    fun drainTimedOut(ledgerApplied: Long, gatewayResults: Long, accepted: Long) =
        "drain timed out: ledger applied $ledgerApplied, gateway results $gatewayResults, of $accepted"

    fun clientReconciliationFailed(error: String) = "client reconciliation failed: $error"
}

/** What the report could not show; none of it decides the verdict. */
object RunNotes {
    const val REPORTING_UNAVAILABLE = "reporting unavailable: no numbers from the bank's events"

    fun projectionBehindBooks(stats: RunStats, gateway: RunSummary, ledger: ReconciliationReport) =
        "projection does not match the books: commands ${stats.totals.commandsSeen} of ${gateway.accepted}, " +
            "posted ${stats.totals.posted} of ${ledger.postedTransfers}, rejected ${stats.totals.rejected} of ${ledger.rejectedTransfers}"
}

@Schema(description = "What the gateway counted for the run.")
data class GatewayCounts(val accepted: Long, val published: Long, val posted: Long, val rejected: Long)

@Schema(description = "What the ledger applied for the run, and the sums of its books.")
data class LedgerCounts(
    val posted: Long,
    val rejected: Long,
    @field:Schema(description = "Sum of the accounts' opening balances.")
    val openingTotalCents: Long,
    @field:Schema(description = "Sum of the accounts' balances after the run.")
    val balanceTotalCents: Long,
    @field:Schema(description = "Sum of the journal's debits, as a positive amount.")
    val debitsCents: Long,
    @field:Schema(description = "Sum of the journal's credits.")
    val creditsCents: Long,
)

@Schema(description = "A crash of the ledger while the run was active.")
data class ChaosInRun(
    @field:Schema(description = "Milliseconds from the clients' start; 0 if the crash came before it.")
    val offsetMs: Long,
    @field:Schema(description = "Instances that took the crash.")
    val instances: List<String>,
)

fun ChaosEvent.inRun(startedAt: Instant) = ChaosInRun(Duration.between(startedAt, at).toMillis().coerceAtLeast(0), instances)

@Schema(description = "The verdict of a run and what it rests on.")
data class RunReport(
    val result: RunResult,
    @field:Schema(description = "What the verdict could not check, with or without breaks; empty if nothing was missing.")
    val reasons: List<String>,
    @field:Schema(description = "The ledger's books: balances, journal and transfer log agree.")
    val ledgerBalanced: Boolean,
    val ledgerFailedChecks: List<ReconciliationCheck>,
    @field:Schema(description = "Gateway and ledger count the same posted and rejected transfers and reasons; null if the drain did not finish.")
    val bankViewsAgree: Boolean?,
    @field:Schema(description = "The clients' journal matches the statement; null without a client reconciliation.")
    val clientBalanced: Boolean?,
    @field:Schema(description = "No statement line was still in flight; null without a client reconciliation.")
    val statementFinal: Boolean?,
    @field:Schema(description = "What the clients were told; null without a client reconciliation.")
    val client: JournalTotals?,
    val gateway: GatewayCounts,
    val ledger: LedgerCounts,
    @field:Schema(description = "Bank latency p50, ms: accepted to result known, by the gateway's clock; null without results.")
    val bankLatencyP50Ms: Double?,
    @field:Schema(description = "Bank latency p99, ms, as bankLatencyP50Ms.")
    val bankLatencyP99Ms: Double?,
    @field:Schema(description = "Rejections by reason, as the ledger decided them.")
    val rejectionsByReason: Map<String, Long>,
    @field:Schema(description = "Discrepancies between journal and statement, by kind, with up to 1000 ids each.")
    val breaks: ReconciliationBreaks?,
    @field:Schema(description = "Transfers the clients did not know the outcome of, settled by the statement.")
    val unknownResolved: UnknownResolved?,
    @field:Schema(description = "Client latency p50, ms: a waiting client's first press to a final status, exact over the run; presses whose wait ran out are left out; null without one.")
    val clientLatencyP50Ms: Double?,
    @field:Schema(description = "Client latency p99, ms, as clientLatencyP50Ms.")
    val clientLatencyP99Ms: Double?,
    @field:Schema(description = "The run's numbers from the bank's events, for the page; null if the reporting service could not be read.")
    val stats: RunStats?,
    @field:Schema(description = "The projection counts what the books count: commands seen as accepted, posted, rejected; null without stats.")
    val projectionMatchesBooks: Boolean?,
    @field:Schema(description = "Redeliveries the ledger skipped, at least this many, from monitoring; null if never read.")
    val ledgerDuplicatesAtLeast: Long?,
    @field:Schema(description = "What the report could not show; does not decide the verdict.")
    val notes: List<String> = emptyList(),
    @field:Schema(description = "Ledger crashes while the run was active, in order; empty if none.")
    val chaos: List<ChaosInRun> = emptyList(),
)

data class RunMeasurements(
    val clientLatencyP50Ms: Double? = null,
    val clientLatencyP99Ms: Double? = null,
    val stats: RunStats? = null,
    val ledgerDuplicatesAtLeast: Long? = null,
    val chaos: List<ChaosInRun> = emptyList(),
)

fun projectionMatchesBooks(stats: RunStats, gateway: RunSummary, ledger: ReconciliationReport): Boolean =
    stats.totals.commandsSeen == gateway.accepted && stats.totals.posted == ledger.postedTransfers &&
        stats.totals.rejected == ledger.rejectedTransfers

sealed interface ClientSide {
    data class Reconciled(val report: ClientReconciliationReport) : ClientSide

    data class Missing(val reason: String) : ClientSide
}

fun drainFinished(gateway: RunSummary, ledger: ReconciliationReport): Boolean =
    gateway.posted + gateway.rejected == gateway.accepted && gateway.accepted == ledger.postedTransfers + ledger.rejectedTransfers

/** Ledger and gateway only learn of transfers the gateway accepted, so more than that is never lag. */
fun overcounted(gateway: RunSummary, ledger: ReconciliationReport): Boolean =
    ledger.postedTransfers + ledger.rejectedTransfers > gateway.accepted || gateway.posted + gateway.rejected > gateway.accepted

fun verdictOf(
    gateway: RunSummary,
    ledger: ReconciliationReport,
    client: ClientSide,
    measurements: RunMeasurements = RunMeasurements(),
): RunReport {
    val drained = drainFinished(gateway, ledger)
    val overcounted = overcounted(gateway, ledger)
    // Before the drain ends the gateway's counts trail the ledger's: a difference then is lag, not a break.
    val bankViewsAgree = when {
        overcounted -> false
        drained -> gateway.posted == ledger.postedTransfers && gateway.rejected == ledger.rejectedTransfers &&
            gateway.rejectionsByReason == ledger.rejectionsByReason
        else -> null
    }
    val clientReport = (client as? ClientSide.Reconciled)?.report
    val reasons = buildList {
        if (!drained && !overcounted) {
            add(RunOutcomes.drainTimedOut(ledger.postedTransfers + ledger.rejectedTransfers, gateway.posted + gateway.rejected, gateway.accepted))
        }
        if (client is ClientSide.Missing) add(client.reason)
        if (clientReport?.statementFinal == false) add(RunOutcomes.STATEMENT_NOT_FINAL)
    }
    val stats = measurements.stats
    val projectionMatches = stats?.let { projectionMatchesBooks(it, gateway, ledger) }
    val notes = when (projectionMatches) {
        null -> listOf(RunNotes.REPORTING_UNAVAILABLE)
        false -> listOf(RunNotes.projectionBehindBooks(checkNotNull(stats), gateway, ledger))
        true -> emptyList()
    }
    val result = when {
        !ledger.balanced || bankViewsAgree == false || clientReport?.balanced == false -> RunResult.BREAKS
        reasons.isNotEmpty() -> RunResult.INCOMPLETE
        else -> RunResult.BALANCED
    }
    return RunReport(
        result = result,
        reasons = reasons,
        ledgerBalanced = ledger.balanced,
        ledgerFailedChecks = ledger.failedChecks,
        bankViewsAgree = bankViewsAgree,
        clientBalanced = clientReport?.balanced,
        statementFinal = clientReport?.statementFinal,
        client = clientReport?.journal,
        gateway = GatewayCounts(gateway.accepted, gateway.published, gateway.posted, gateway.rejected),
        ledger = LedgerCounts(
            ledger.postedTransfers, ledger.rejectedTransfers,
            ledger.openingTotalCents, ledger.balanceTotalCents, ledger.debitsCents, ledger.creditsCents,
        ),
        bankLatencyP50Ms = gateway.latencyP50Ms,
        bankLatencyP99Ms = gateway.latencyP99Ms,
        rejectionsByReason = ledger.rejectionsByReason,
        breaks = clientReport?.breaks,
        unknownResolved = clientReport?.unknownResolved,
        clientLatencyP50Ms = measurements.clientLatencyP50Ms,
        clientLatencyP99Ms = measurements.clientLatencyP99Ms,
        stats = stats,
        projectionMatchesBooks = projectionMatches,
        ledgerDuplicatesAtLeast = measurements.ledgerDuplicatesAtLeast,
        notes = notes,
        chaos = measurements.chaos,
    )
}
