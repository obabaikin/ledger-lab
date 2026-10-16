package com.ledgerlab.generator.bank

import java.util.UUID

/*
 * The generator's own model of the bank's published API (gateway/openapi.json). It shares no classes
 * with the bank; BankContractTest checks it against the spec (ADR 0011).
 */

data class SubmitTransferRequest(
    val transferId: UUID,
    val runId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
)

data class TransferResponse(
    val transferId: UUID,
    val runId: UUID,
    val status: TransferStatus,
    val rejectReason: RejectReason?,
)

enum class TransferStatus {
    ACCEPTED,
    PUBLISHED,
    POSTED,
    REJECTED;

    val isFinal: Boolean get() = this == POSTED || this == REJECTED
}

enum class RejectReason {
    INVALID_AMOUNT,
    SAME_ACCOUNT,
    UNKNOWN_ACCOUNT,
    BLOCKED_ACCOUNT,
    INSUFFICIENT_FUNDS,
    DAILY_LIMIT_EXCEEDED,
}

data class Statement(
    val entryCount: Long,
    val items: List<StatementLine>,
    val next: UUID?,
)

data class StatementLine(
    val transferId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
    /** Null if the bank is newer than this client and sent a status it does not know (ADR 0010). */
    val status: TransferStatus?,
    val rejectReason: RejectReason?,
)
