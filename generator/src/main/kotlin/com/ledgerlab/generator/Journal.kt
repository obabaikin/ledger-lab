package com.ledgerlab.generator

import com.ledgerlab.common.TransferCommand
import java.util.UUID

/** How one send of a transfer ended, as the client saw it. */
enum class SendResult {
    /** The gateway answered; the code is in httpStatus. */
    RESPONSE,
    /** No connection: the request never left. */
    NOT_CONNECTED,
    /** Timeout or broken connection after the request may have left. */
    NO_RESPONSE,
    /** Cancelled by a stop while waiting for the answer. */
    CANCELLED,
}

enum class ResendKind { RETRY }

enum class JournalOutcome { PENDING, ACKNOWLEDGED, FAILED, UNKNOWN }

/** Offsets are on the generator's monotonic clock, counted from the start of the run. */
data class Send(val offsetMs: Long, val result: SendResult, val httpStatus: Int?, val latencyMs: Long)

data class Resend(
    val kind: ResendKind,
    val number: Int,
    val offsetMs: Long,
    val result: SendResult,
    val httpStatus: Int?,
    val latencyMs: Long,
)

data class JournalEntryView(
    val seq: Long,
    val transferId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
    val outcome: JournalOutcome,
    val gatewayStatus: String?,
    val rejectReason: String?,
    /** Null while the first send still waits for its answer: a send is recorded when it ends. */
    val first: Send?,
    val resends: List<Resend>,
)

data class JournalPage(val items: List<JournalEntryView>, val next: Long?)

data class JournalFilter(
    val outcome: JournalOutcome? = null,
    val resent: Boolean? = null,
    val rejectReason: String? = null,
) {
    fun matches(entry: JournalEntryView): Boolean =
        (outcome == null || entry.outcome == outcome) &&
            (resent == null || entry.resends.isNotEmpty() == resent) &&
            (rejectReason == null || entry.rejectReason == rejectReason)
}

/** Receives every send of one transfer as it ends, so a send cut short by cancellation is still recorded. */
fun interface SendRecorder {
    fun record(startedNanos: Long, endedNanos: Long, result: SendResult, httpStatus: Int?)

    companion object {
        val NONE = SendRecorder { _, _, _, _ -> }
    }
}

/**
 * One transfer as the client knows it. Only its own submission coroutine writes it; API threads
 * read it, so every field a reader sees is published through a volatile write.
 */
class JournalEntry(
    val seq: Long,
    private val command: TransferCommand,
    private val runStartNanos: Long,
) : SendRecorder {
    @Volatile var outcome: JournalOutcome = JournalOutcome.PENDING
        private set
    @Volatile private var gatewayStatus: String? = null
    @Volatile private var rejectReason: String? = null
    @Volatile private var first: Send? = null
    @Volatile private var resends: List<Resend> = emptyList()

    val transferId: UUID get() = command.transferId

    override fun record(startedNanos: Long, endedNanos: Long, result: SendResult, httpStatus: Int?) {
        val offsetMs = (startedNanos - runStartNanos) / 1_000_000
        val latencyMs = (endedNanos - startedNanos) / 1_000_000
        if (first == null) {
            first = Send(offsetMs, result, httpStatus, latencyMs)
        } else {
            resends = resends + Resend(ResendKind.RETRY, resends.size + 2, offsetMs, result, httpStatus, latencyMs)
        }
    }

    fun settle(outcome: JournalOutcome, gatewayStatus: String? = null, rejectReason: String? = null) {
        this.gatewayStatus = gatewayStatus
        this.rejectReason = rejectReason
        this.outcome = outcome
    }

    fun view() = JournalEntryView(
        seq, command.transferId, command.fromAccount, command.toAccount, command.amountCents,
        outcome, gatewayStatus, rejectReason, first, resends,
    )
}

/** Every transfer of one run, in the order of first send; seq starts at 1 and is the paging cursor. */
class Journal(private val runStartNanos: Long) {
    private val entries = ArrayList<JournalEntry>()
    private val byId = HashMap<UUID, JournalEntry>()

    @Synchronized
    fun add(command: TransferCommand): JournalEntry {
        val entry = JournalEntry(entries.size + 1L, command, runStartNanos)
        entries.add(entry)
        byId[command.transferId] = entry
        return entry
    }

    @Synchronized
    fun get(transferId: UUID): JournalEntryView? = byId[transferId]?.view()

    /** [complete]: no entry will be added any more, so reaching the end means there is no next page. */
    @Synchronized
    fun page(filter: JournalFilter, after: Long, limit: Int, complete: Boolean): JournalPage {
        val items = ArrayList<JournalEntryView>()
        var index = after.coerceIn(0, entries.size.toLong()).toInt()
        while (index < entries.size && items.size < limit) {
            val view = entries[index++].view()
            if (filter.matches(view)) items.add(view)
        }
        val next = if (index < entries.size || !complete) index.toLong() else null
        return JournalPage(items, next)
    }
}
