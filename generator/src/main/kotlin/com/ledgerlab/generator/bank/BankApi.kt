package com.ledgerlab.generator.bank

import java.time.Instant
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
    val acceptedAt: Instant,
    val resultedAt: Instant?,
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
