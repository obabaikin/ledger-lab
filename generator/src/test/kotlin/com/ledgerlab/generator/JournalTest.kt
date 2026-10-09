package com.ledgerlab.generator

import com.ledgerlab.common.TransferCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class JournalTest {

    private val start = System.nanoTime()
    private val journal = Journal(start)

    private fun add(): JournalEntry =
        journal.add(TransferCommand(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100, Instant.now()))

    private fun respond(entry: JournalEntry, httpStatus: Int) =
        entry.record(start, start + 1_000_000, SendResult.RESPONSE, httpStatus)

    @Test
    fun `pages follow the order of first send and end only when the journal is complete`() {
        repeat(5) { respond(add(), 202) }

        val first = journal.page(JournalFilter(), after = 0, limit = 2, complete = true)
        val second = journal.page(JournalFilter(), after = first.next!!, limit = 2, complete = true)
        val last = journal.page(JournalFilter(), after = second.next!!, limit = 2, complete = true)

        assertThat(first.items.map { it.seq }).containsExactly(1, 2)
        assertThat(second.items.map { it.seq }).containsExactly(3, 4)
        assertThat(last.items.map { it.seq }).containsExactly(5)
        assertThat(last.next).isNull()

        val live = journal.page(JournalFilter(), after = 0, limit = 100, complete = false)
        assertThat(live.items).hasSize(5)
        assertThat(live.next).isEqualTo(5)
        val nextEntry = add()
        assertThat(journal.page(JournalFilter(), after = live.next!!, limit = 100, complete = false).items.map { it.seq })
            .containsExactly(nextEntry.seq)
    }

    @Test
    fun `filters combine and skip entries without losing the cursor`() {
        val pending = add()
        val retried = add().also { respond(it, 503); respond(it, 202); it.settle(JournalOutcome.ACKNOWLEDGED, "ACCEPTED") }
        val rejected = add().also { respond(it, 200); it.settle(JournalOutcome.ACKNOWLEDGED, "REJECTED", "BLOCKED_ACCOUNT") }
        val failed = add().also { respond(it, 422); it.settle(JournalOutcome.FAILED) }

        fun ids(filter: JournalFilter) = journal.page(filter, 0, 100, complete = true).items.map { it.transferId }

        assertThat(ids(JournalFilter(outcome = JournalOutcome.PENDING))).containsExactly(pending.transferId)
        assertThat(ids(JournalFilter(resent = true))).containsExactly(retried.transferId)
        assertThat(ids(JournalFilter(rejectReason = "BLOCKED_ACCOUNT"))).containsExactly(rejected.transferId)
        assertThat(ids(JournalFilter(outcome = JournalOutcome.ACKNOWLEDGED, resent = false))).containsExactly(rejected.transferId)
        assertThat(ids(JournalFilter(outcome = JournalOutcome.FAILED))).containsExactly(failed.transferId)

        val page = journal.page(JournalFilter(resent = true), after = 0, limit = 1, complete = true)
        assertThat(page.next).isEqualTo(retried.seq)
        assertThat(journal.page(JournalFilter(resent = true), after = page.next!!, limit = 1, complete = true))
            .isEqualTo(JournalPage(emptyList(), null))
    }

    @Test
    fun `the first send is the entry itself, later ones are numbered from 2`() {
        val entry = add()
        entry.record(start + 2_000_000, start + 5_000_000, SendResult.NO_RESPONSE, null)
        entry.record(start + 1_005_000_000, start + 1_010_000_000, SendResult.RESPONSE, 202)

        val view = journal.get(entry.transferId)!!
        assertThat(view.first).isEqualTo(Send(offsetMs = 2, result = SendResult.NO_RESPONSE, httpStatus = null, latencyMs = 3))
        assertThat(view.resends).containsExactly(Resend(ResendKind.RETRY, 2, 1_005, SendResult.RESPONSE, 202, 5))
        assertThat(journal.get(UUID.randomUUID())).isNull()
    }
}
