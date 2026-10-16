package com.ledgerlab.gateway.domain

import com.ledgerlab.common.RejectReason
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "A transfer's state at the bank; POSTED and REJECTED are final.")
enum class TransferStatus {
    ACCEPTED,
    PUBLISHED,
    POSTED,
    REJECTED;

    val isFinal: Boolean get() = this == POSTED || this == REJECTED
}

const val REJECT_REASON_DESCRIPTION = "Set when status is REJECTED; null if the reason is newer than this API version."

/** The reason as the API shows it; a stored reason this gateway does not know yet reads as null (ADR 0011). */
fun rejectReasonOf(stored: String): RejectReason? = RejectReason.entries.find { it.name == stored }

data class Transfer(
    val transferId: UUID,
    val runId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
    val status: TransferStatus,
    val rejectReason: RejectReason?,
    val acceptedAt: Instant,
    val resultedAt: Instant?,
)

data class RunSummary(
    val runId: UUID,
    val accepted: Long,
    val published: Long,
    val posted: Long,
    val rejected: Long,
    /** Keyed by the reason as stored, so one this gateway does not know yet is counted too. */
    val rejectionsByReason: Map<String, Long>,
    val latencyP50Ms: Double?,
    val latencyP99Ms: Double?,
)

data class RunPeriod(val runId: UUID, val closedAt: Instant, val entryCount: Long)

@Schema(description = "A transfer the bank accepted in the closed period.")
data class StatementLine(
    val transferId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
    val status: TransferStatus,
    @field:Schema(description = REJECT_REASON_DESCRIPTION)
    val rejectReason: RejectReason?,
    val acceptedAt: Instant,
    @field:Schema(description = "When the final status was recorded; null while not final.")
    val resultedAt: Instant?,
)

@Schema(description = "One page of a closed period's statement.")
data class Statement(
    val runId: UUID,
    @field:Schema(description = "When the period was closed; no transfer of the run is accepted after it.")
    val closedAt: Instant,
    @field:Schema(
        description = "Lines in the whole statement, any status; the same on every page.",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val entryCount: Long,
    val items: List<StatementLine>,
    @field:Schema(description = "Pass as `after` for the next page; null on the last page.")
    val next: UUID?,
)
