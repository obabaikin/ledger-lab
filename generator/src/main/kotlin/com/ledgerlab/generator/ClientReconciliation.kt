package com.ledgerlab.generator

import com.ledgerlab.generator.bank.StatementLine
import com.ledgerlab.generator.bank.TransferStatus
import java.util.EnumMap
import java.util.UUID

/** Transfers of one kind: [count] is exact, [transferIds] holds at most the first [MAX_LISTED_IDS]. */
data class TransferIds(val count: Long, val transferIds: List<UUID>)

/** Any transfer here makes the run unbalanced (ADR 0010). */
data class ReconciliationBreaks(
    /** In the bank's statement, unknown to the client. */
    val notInJournal: TransferIds,
    /** Acknowledged to the client with a 2xx, absent from the statement. */
    val acknowledgedNotInStatement: TransferIds,
    /** Refused to the client with a 4xx, yet accepted by the bank. */
    val failedButInStatement: TransferIds,
    /** Amount or accounts differ between the client's record and the bank's. */
    val payloadMismatch: TransferIds,
    /** The client was told a final status, and the bank has another status or reject reason. */
    val statusMismatch: TransferIds,
)

/** Transfers the client could not settle, settled by the statement; informational, never a break. */
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
    /**
     * False while the ledger is still applying transfers, or while a line's status cannot be read;
     * the verdict is final only when this is true (ADR 0010).
     */
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

/**
 * Matches the client's journal against the bank's statement by transfer id. Statement lines are
 * fed page by page, so only the ids seen so far are held, not the statement.
 */
/**
 * Pending only remains if the run ended while the transfer was being sent, e.g. by a stop: the
 * request may have reached the bank, so the statement settles it like an unknown.
 */
private val JournalOutcome.unsettled: Boolean get() = this == JournalOutcome.UNKNOWN || this == JournalOutcome.PENDING

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
                (entry.gatewayStatus != line.status || entry.rejectReason != line.rejectReason)
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
                unknown = entries.values.count { it.outcome.unsettled }.toLong(),
                acknowledgedAmountCents = acknowledged.sumOf { it.amountCents },
            ),
            statement = StatementTotals(rows, amountCents, byStatus.toMap()),
            breaks = breaks,
            unknownResolved = UnknownResolved(unknownAccepted.result(), unknownNotAccepted.result()),
        )
    }
}
