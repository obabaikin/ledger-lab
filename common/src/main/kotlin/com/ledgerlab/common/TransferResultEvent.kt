package com.ledgerlab.common

import java.time.Instant
import java.util.UUID

/** Published by the ledger after a transfer has been posted or rejected. */
data class TransferResultEvent(
    val runId: UUID,
    val transferId: UUID,
    val outcome: TransferOutcome,
    val rejectReason: String?,
    val processedAt: Instant,
) {
    fun partitionKey(): String = "$runId:$transferId"
}
