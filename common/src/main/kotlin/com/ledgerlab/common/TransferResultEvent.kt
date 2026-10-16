package com.ledgerlab.common

import java.time.Instant
import java.util.UUID

data class TransferResultEvent(
    val runId: UUID,
    val transferId: UUID,
    val outcome: TransferOutcome,
    val rejectReason: RejectReason?,
    val processedAt: Instant,
) {
    fun partitionKey(): String = partitionKey(runId, transferId)

    companion object {
        fun partitionKey(runId: UUID, transferId: UUID): String = "$runId:$transferId"
    }
}
