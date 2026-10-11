package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.TransferStatus
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class SendResult {
    RESPONSE,
    /** No connection: the request never left. */
    NOT_CONNECTED,
    /** Timeout or broken connection after the request may have left. */
    NO_RESPONSE,
    CANCELLED,
}

/** What a send was, from the client's side; the gateway cannot tell a retry from a duplicate. */
enum class SendKind { FIRST, RETRY, DUPLICATE }

enum class ResendKind { RETRY, DUPLICATE }

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
    val gatewayStatus: TransferStatus?,
    val rejectReason: RejectReason?,
    /** The first attempt of the submission, even when a duplicate ended earlier; null while it waits for its answer. */
    val first: Send?,
    val resends: List<Resend>,
)

data class JournalPage(val items: List<JournalEntryView>, val next: Long?)

data class JournalFilter(
    val outcome: JournalOutcome? = null,
    val resent: Boolean? = null,
    val rejectReason: RejectReason? = null,
) {
    fun matches(entry: JournalEntryView): Boolean =
        (outcome == null || entry.outcome == outcome) &&
            (resent == null || entry.resends.isNotEmpty() == resent) &&
            (rejectReason == null || entry.rejectReason == rejectReason)
}

/** Receives every send of one submission as it ends, so a send cut short by cancellation is still recorded. */
fun interface SendRecorder {
    fun record(attempt: Int, startedNanos: Long, endedNanos: Long, result: SendResult, httpStatus: Int?)

    companion object {
        val NONE = SendRecorder { _, _, _, _, _ -> }
    }
}

/**
 * One transfer as the client knows it. Its submission and its duplicate may record sends at the
 * same time; API threads read it, so every field a reader sees is published through a volatile write.
 */
class JournalEntry(
    val seq: Long,
    private val transfer: PlannedTransfer,
    private val runStartNanos: Long,
) {
    @Volatile var outcome: JournalOutcome = JournalOutcome.PENDING
        private set
    @Volatile private var gatewayStatus: TransferStatus? = null
    @Volatile private var rejectReason: RejectReason? = null
    @Volatile private var first: Send? = null
    @Volatile private var resends: List<Resend> = emptyList()

    val transferId: UUID get() = transfer.transferId

    /** Resends are numbered in the order they end, so the list only grows at its end while it is read. */
    @Synchronized
    fun record(kind: SendKind, startedNanos: Long, endedNanos: Long, result: SendResult, httpStatus: Int?) {
        val offsetMs = TimeUnit.NANOSECONDS.toMillis(startedNanos - runStartNanos)
        val latencyMs = TimeUnit.NANOSECONDS.toMillis(endedNanos - startedNanos)
        val resendKind = when (kind) {
            SendKind.FIRST -> {
                first = Send(offsetMs, result, httpStatus, latencyMs)
                return
            }
            SendKind.RETRY -> ResendKind.RETRY
            SendKind.DUPLICATE -> ResendKind.DUPLICATE
        }
        resends = resends + Resend(resendKind, FIRST_ATTEMPT + resends.size + 1, offsetMs, result, httpStatus, latencyMs)
    }

    fun settle(outcome: JournalOutcome, gatewayStatus: TransferStatus? = null, rejectReason: RejectReason? = null) {
        this.gatewayStatus = gatewayStatus
        this.rejectReason = rejectReason
        this.outcome = outcome
    }

    fun view() = JournalEntryView(
        seq, transfer.transferId, transfer.fromAccount, transfer.toAccount, transfer.amountCents,
        outcome, gatewayStatus, rejectReason, first, resends,
    )
}

/** Every transfer of one run, in the order of first send; seq starts at 1 and is the paging cursor. */
class Journal(private val runStartNanos: Long) {
    private val entries = ArrayList<JournalEntry>()
    private val byId = HashMap<UUID, JournalEntry>()

    @Synchronized
    fun add(transfer: PlannedTransfer): JournalEntry {
        val entry = JournalEntry(entries.size + 1L, transfer, runStartNanos)
        entries.add(entry)
        byId[transfer.transferId] = entry
        return entry
    }

    @Synchronized
    fun get(transferId: UUID): JournalEntryView? = byId[transferId]?.view()

    @Synchronized
    fun entries(): List<JournalEntryView> = entries.map { it.view() }

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
