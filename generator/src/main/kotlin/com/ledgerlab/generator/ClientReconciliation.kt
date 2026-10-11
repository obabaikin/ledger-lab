package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.StatementLine
import com.ledgerlab.generator.bank.TransferStatus
import java.util.EnumMap
import java.util.UUID

data class TransferIds(val count: Long, val transferIds: List<UUID>)

/** Each kind is defined in ADR 0010; any transfer here makes the report unbalanced. */
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

/** [byStatus] leaves out lines whose status this client cannot read; [rows] counts them. */
data class StatementTotals(val rows: Long, val amountCents: Long, val byStatus: Map<TransferStatus, Long>)

data class ClientReconciliationReport(
    val runId: UUID,
    val balanced: Boolean,
    val statementFinal: Boolean,
    val journal: JournalTotals,
    val statement: StatementTotals,
    val breaks: ReconciliationBreaks,
    val unknownResolved: UnknownResolved,
)

const val MAX_LISTED_IDS = 1_000

private class IdCollector {
    private var count = 0L
    private val ids = ArrayList<UUID>()

    fun add(id: UUID) {
        count++
        if (ids.size < MAX_LISTED_IDS) ids.add(id)
    }

    fun result() = TransferIds(count, ids.toList())
}

private val JournalOutcome.settledByStatement: Boolean get() = this == JournalOutcome.UNKNOWN || this == JournalOutcome.PENDING

private fun knownReasonsDiffer(client: RejectReason?, bank: RejectReason?): Boolean = client != null && bank != null && client != bank

/** Statement lines are fed page by page: only the ids seen so far are held, not the statement. */
class ClientReconciliation(private val runId: UUID, journal: List<JournalEntryView>) {
    private val entries = journal.associateBy { it.transferId }
    private val inStatement = HashSet<UUID>()
    private val notInJournal = IdCollector()
    private val failedButInStatement = IdCollector()
    private val payloadMismatch = IdCollector()
    private val statusMismatch = IdCollector()
    private val unknownAccepted = IdCollector()
    private var rows = 0L
    private var amountCents = 0L
    private val byStatus = EnumMap<TransferStatus, Long>(TransferStatus::class.java)
    private var unreadableStatus = false

    fun add(line: StatementLine) {
        rows++
        amountCents += line.amountCents
        if (line.status == null) unreadableStatus = true else byStatus.merge(line.status, 1L, Long::plus)
        inStatement.add(line.transferId)
        val entry = entries[line.transferId] ?: return notInJournal.add(line.transferId)
        if (entry.amountCents != line.amountCents || entry.fromAccount != line.fromAccount || entry.toAccount != line.toAccount) {
            payloadMismatch.add(line.transferId)
        }
        when (entry.outcome) {
            JournalOutcome.FAILED -> failedButInStatement.add(line.transferId)
            JournalOutcome.UNKNOWN, JournalOutcome.PENDING -> unknownAccepted.add(line.transferId)
            JournalOutcome.ACKNOWLEDGED -> if (entry.gatewayStatus?.isFinal == true && line.status != null &&
                (entry.gatewayStatus != line.status || knownReasonsDiffer(entry.rejectReason, line.rejectReason))
            ) {
                statusMismatch.add(line.transferId)
            }
        }
    }

    fun report(): ClientReconciliationReport {
        val acknowledgedNotInStatement = IdCollector()
        val unknownNotAccepted = IdCollector()
        for (entry in entries.values) {
            if (entry.transferId in inStatement) continue
            when (entry.outcome) {
                JournalOutcome.ACKNOWLEDGED -> acknowledgedNotInStatement.add(entry.transferId)
                JournalOutcome.UNKNOWN, JournalOutcome.PENDING -> unknownNotAccepted.add(entry.transferId)
                JournalOutcome.FAILED -> {}
            }
        }
        val breaks = ReconciliationBreaks(
            notInJournal.result(), acknowledgedNotInStatement.result(), failedButInStatement.result(),
            payloadMismatch.result(), statusMismatch.result(),
        )
        val acknowledged = entries.values.filter { it.outcome == JournalOutcome.ACKNOWLEDGED }
        return ClientReconciliationReport(
            runId = runId,
            balanced = listOf(
                breaks.notInJournal, breaks.acknowledgedNotInStatement, breaks.failedButInStatement,
                breaks.payloadMismatch, breaks.statusMismatch,
            ).all { it.count == 0L },
            statementFinal = !unreadableStatus && byStatus.keys.all { it.isFinal },
            journal = JournalTotals(
                requested = entries.size.toLong(),
                acknowledged = acknowledged.size.toLong(),
                failed = entries.values.count { it.outcome == JournalOutcome.FAILED }.toLong(),
                unknown = entries.values.count { it.outcome.settledByStatement }.toLong(),
                acknowledgedAmountCents = acknowledged.sumOf { it.amountCents },
            ),
            statement = StatementTotals(rows, amountCents, byStatus.toMap()),
            breaks = breaks,
            unknownResolved = UnknownResolved(unknownAccepted.result(), unknownNotAccepted.result()),
        )
    }
}
