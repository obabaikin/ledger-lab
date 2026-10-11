package com.ledgerlab.gateway.service

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
    private val waiters = ConcurrentHashMap<UUID, CopyOnWriteArrayList<CompletableFuture<LedgerResult>>>()

    fun register(transferId: UUID): CompletableFuture<LedgerResult> {
        val future = CompletableFuture<LedgerResult>()
        waiters.compute(transferId) { _, list -> (list ?: CopyOnWriteArrayList()).also { it.add(future) } }
        return future
    }

    fun remove(transferId: UUID, future: CompletableFuture<LedgerResult>) {
        waiters.computeIfPresent(transferId) { _, list ->
            list.remove(future)
            list.ifEmpty { null }
        }
    }

    fun complete(result: LedgerResult) {
        waiters.remove(result.transferId)?.forEach { it.complete(result) }
    }

    fun size(): Int = waiters.values.sumOf { it.size }
}
