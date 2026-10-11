package com.ledgerlab.gateway.domain

import java.time.Instant
import java.util.UUID

enum class TransferStatus {
    ACCEPTED,
    PUBLISHED,
    POSTED,
    REJECTED;

    val isFinal: Boolean get() = this == POSTED || this == REJECTED
}

data class Transfer(
    val transferId: UUID,
    val runId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
    val status: TransferStatus,
    val rejectReason: String?,
    val acceptedAt: Instant,
    val publishedAt: Instant?,
    val resultedAt: Instant?,
)

data class RunSummary(
    val runId: UUID,
    val accepted: Long,
    val published: Long,
    val posted: Long,
    val rejected: Long,
    val rejectionsByReason: Map<String, Long>,
    val latencyP50Ms: Double?,
    val latencyP99Ms: Double?,
) {
    val pending: Long = accepted - posted - rejected
}

data class RunPeriod(val runId: UUID, val closedAt: Instant)

data class StatementLine(
    val transferId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
    val status: TransferStatus,
    val rejectReason: String?,
    val acceptedAt: Instant,
    val resultedAt: Instant?,
)

data class Statement(val runId: UUID, val closedAt: Instant, val items: List<StatementLine>, val next: UUID?)
