package com.ledgerlab.common

import java.time.Instant
import java.util.UUID

data class TransferCommand(
    val runId: UUID,
    val transferId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
    val createdAt: Instant,
) {
    /** Partition key: keeps all transfers of one sender in one partition and isolates runs. */
    fun partitionKey(): String = "$runId:$fromAccount"
}
