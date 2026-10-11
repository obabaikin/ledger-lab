package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.StatementLine
import com.ledgerlab.generator.bank.TransferStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ClientReconciliationTest {

    private val runId = UUID.randomUUID()
    private var seq = 0L

    private fun entry(
        outcome: JournalOutcome,
        status: TransferStatus? = null,
        reason: RejectReason? = null,
        amountCents: Long = 100,
    ) = JournalEntryView(++seq, UUID.randomUUID(), 1, 2, amountCents, outcome, status, reason, null, emptyList())

    private fun line(
        entry: JournalEntryView,
        status: TransferStatus? = TransferStatus.POSTED,
        reason: RejectReason? = null,
        amountCents: Long = entry.amountCents,
        fromAccount: Int = entry.fromAccount,
        toAccount: Int = entry.toAccount,
    ) = StatementLine(entry.transferId, fromAccount, toAccount, amountCents, status, reason)

    private fun reconcile(journal: List<JournalEntryView>, statement: List<StatementLine>): ClientReconciliationReport =
        ClientReconciliation(runId, journal).apply { statement.forEach(::add) }.report()

    private fun ReconciliationBreaks.all() =
        listOf(notInJournal, acknowledgedNotInStatement, failedButInStatement, payloadMismatch, statusMismatch)

    @Test
    fun `an empty run against an empty statement is balanced`() {
        val report = reconcile(emptyList(), emptyList())

        assertThat(report.balanced).isTrue()
        assertThat(report.statementFinal).isTrue()
        assertThat(report.journal).isEqualTo(JournalTotals(0, 0, 0, 0, 0))
        assertThat(report.statement).isEqualTo(StatementTotals(0, 0, emptyMap()))
    }

    @Test
    fun `every acknowledged transfer found with the same payload and status is balanced`() {
        val posted = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, amountCents = 300)
        val rejected = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT)
        val accepted = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED)

        val report = reconcile(
            listOf(posted, rejected, accepted),
            listOf(line(posted), line(rejected, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT), line(accepted)),
        )

        assertThat(report.balanced).isTrue()
        assertThat(report.breaks.all()).allSatisfy { assertThat(it.count).isZero() }
        assertThat(report.journal).isEqualTo(JournalTotals(3, 3, 0, 0, 500))
        assertThat(report.statement).isEqualTo(
            StatementTotals(3, 500, mapOf(TransferStatus.POSTED to 2L, TransferStatus.REJECTED to 1L)),
        )
    }

    @Test
    fun `a transfer the bank has and the client does not know breaks the run`() {
        val stranger = entry(JournalOutcome.ACKNOWLEDGED)

        val report = reconcile(emptyList(), listOf(line(stranger)))

        assertThat(report.balanced).isFalse()
        assertThat(report.breaks.notInJournal).isEqualTo(TransferIds(1, listOf(stranger.transferId)))
    }

    @Test
    fun `a transfer acknowledged to the client and missing from the statement breaks the run`() {
        val lost = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED)

        val report = reconcile(listOf(lost), emptyList())

        assertThat(report.balanced).isFalse()
        assertThat(report.breaks.acknowledgedNotInStatement).isEqualTo(TransferIds(1, listOf(lost.transferId)))
    }

    @Test
    fun `a transfer refused to the client and accepted by the bank breaks the run`() {
        val refused = entry(JournalOutcome.FAILED)
        val refusedForGood = entry(JournalOutcome.FAILED)

        val report = reconcile(listOf(refused, refusedForGood), listOf(line(refused)))

        assertThat(report.balanced).isFalse()
        assertThat(report.breaks.failedButInStatement).isEqualTo(TransferIds(1, listOf(refused.transferId)))
        assertThat(report.journal.failed).isEqualTo(2)
    }

    @Test
    fun `a different amount, sending or receiving account breaks the run`() {
        val amount = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED)
        val sender = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED)
        val receiver = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED)

        val report = reconcile(
            listOf(amount, sender, receiver),
            listOf(
                line(amount, amountCents = amount.amountCents + 1),
                line(sender, fromAccount = sender.fromAccount + 1),
                line(receiver, toAccount = receiver.toAccount + 1),
            ),
        )

        assertThat(report.balanced).isFalse()
        assertThat(report.breaks.payloadMismatch.transferIds)
            .containsExactlyInAnyOrder(amount.transferId, sender.transferId, receiver.transferId)
    }

    @Test
    fun `a final status or reject reason the bank does not confirm breaks the run`() {
        val postedToClient = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED)
        val otherReason = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS)

        val report = reconcile(
            listOf(postedToClient, otherReason),
            listOf(
                line(postedToClient, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT),
                line(otherReason, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT),
            ),
        )

        assertThat(report.balanced).isFalse()
        assertThat(report.breaks.statusMismatch.transferIds).containsExactlyInAnyOrder(postedToClient.transferId, otherReason.transferId)
    }

    @Test
    fun `a status the client knows only as accepted, or could not read, is not compared`() {
        val waiting = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED)
        val published = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.PUBLISHED)
        val unread = entry(JournalOutcome.ACKNOWLEDGED, status = null)

        val report = reconcile(
            listOf(waiting, published, unread),
            listOf(line(waiting, TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS), line(published), line(unread, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT)),
        )

        assertThat(report.balanced).isTrue()
    }

    @Test
    fun `unknown transfers are settled by the statement without breaking the run`() {
        val arrived = entry(JournalOutcome.UNKNOWN)
        val neverArrived = entry(JournalOutcome.UNKNOWN)

        val report = reconcile(listOf(arrived, neverArrived), listOf(line(arrived)))

        assertThat(report.balanced).isTrue()
        assertThat(report.unknownResolved).isEqualTo(
            UnknownResolved(TransferIds(1, listOf(arrived.transferId)), TransferIds(1, listOf(neverArrived.transferId))),
        )
        assertThat(report.journal.unknown).isEqualTo(2)
    }

    @Test
    fun `a statement the ledger has not finished is reported as not final, with the same verdict`() {
        val first = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED)
        val second = entry(JournalOutcome.UNKNOWN)
        val journal = listOf(first, second)

        val early = reconcile(journal, listOf(line(first, TransferStatus.ACCEPTED), line(second, TransferStatus.PUBLISHED)))
        val late = reconcile(journal, listOf(line(first), line(second)))

        assertThat(early.statementFinal).isFalse()
        assertThat(late.statementFinal).isTrue()
        assertThat(early.copy(statementFinal = true, statement = late.statement)).isEqualTo(late)
    }

    @Test
    fun `a transfer still pending when the run ended is settled by the statement like an unknown`() {
        val found = entry(JournalOutcome.PENDING)
        val lost = entry(JournalOutcome.PENDING)

        val report = reconcile(listOf(found, lost), listOf(line(found, TransferStatus.POSTED)))

        assertThat(report.balanced).isTrue()
        assertThat(report.journal.unknown).isEqualTo(2)
        assertThat(report.unknownResolved.accepted.transferIds).containsExactly(found.transferId)
        assertThat(report.unknownResolved.notAccepted.transferIds).containsExactly(lost.transferId)
    }

    @Test
    fun `a line whose status the client cannot read is not compared and keeps the statement from being final`() {
        val posted = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED)
        val rejected = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT)

        val report = reconcile(listOf(posted, rejected), listOf(line(posted, status = null), line(rejected, status = null)))

        assertThat(report.breaks.statusMismatch.count).isZero()
        assertThat(report.balanced).isTrue()
        assertThat(report.statementFinal).isFalse()
        assertThat(report.statement.rows).isEqualTo(2)
        assertThat(report.statement.byStatus).isEmpty()
    }

    @Test
    fun `a category lists at most a thousand ids and counts all of them`() {
        val strangers = (1..MAX_LISTED_IDS + 1).map { entry(JournalOutcome.ACKNOWLEDGED) }

        val report = reconcile(emptyList(), strangers.map { line(it) })

        assertThat(report.breaks.notInJournal.count).isEqualTo(MAX_LISTED_IDS + 1L)
        assertThat(report.breaks.notInJournal.transferIds).hasSize(MAX_LISTED_IDS)
    }
}
