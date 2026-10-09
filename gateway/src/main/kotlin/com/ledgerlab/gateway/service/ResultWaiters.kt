package com.ledgerlab.gateway.service

import com.ledgerlab.common.TransferResultEvent
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Open HTTP requests waiting for a result, keyed by transfer id. Local to this instance.
 * Several requests may wait for the same transfer (a client retrying while the first call
 * is still open); each gets its own future, and one leaving does not orphan the others.
 */
@Component
class ResultWaiters {
    private val waiters = ConcurrentHashMap<UUID, CopyOnWriteArrayList<CompletableFuture<TransferResultEvent>>>()

    fun register(transferId: UUID): CompletableFuture<TransferResultEvent> {
        val future = CompletableFuture<TransferResultEvent>()
        waiters.compute(transferId) { _, list -> (list ?: CopyOnWriteArrayList()).also { it.add(future) } }
        return future
    }

    fun remove(transferId: UUID, future: CompletableFuture<TransferResultEvent>) {
        waiters.computeIfPresent(transferId) { _, list ->
            list.remove(future)
            list.ifEmpty { null }
        }
    }

    fun complete(event: TransferResultEvent) {
        waiters.remove(event.transferId)?.forEach { it.complete(event) }
    }

    fun size(): Int = waiters.values.sumOf { it.size }
}
