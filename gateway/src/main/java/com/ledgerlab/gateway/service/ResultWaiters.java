package com.ledgerlab.gateway.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * Open HTTP requests waiting for a result, keyed by transfer id. Local to this instance.
 * Several requests may wait for the same transfer (a client retrying while the first call
 * is still open); each gets its own future, and one leaving does not orphan the others.
 */
@Component
public class ResultWaiters {

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<CompletableFuture<LedgerResult>>> waiters = new ConcurrentHashMap<>();

    public CompletableFuture<LedgerResult> register(UUID transferId) {
        var future = new CompletableFuture<LedgerResult>();
        waiters.compute(transferId, (id, list) -> {
            var futures = list != null ? list : new CopyOnWriteArrayList<CompletableFuture<LedgerResult>>();
            futures.add(future);
            return futures;
        });
        return future;
    }

    // Its own future, not an equal one: each waiting request leaves alone.
    @SuppressWarnings("ReferenceEquality")
    public void remove(UUID transferId, CompletableFuture<LedgerResult> future) {
        waiters.computeIfPresent(transferId, (id, list) -> {
            list.removeIf(it -> it == future);
            return list.isEmpty() ? null : list;
        });
    }

    public void complete(LedgerResult result) {
        var futures = waiters.remove(result.transferId());
        if (futures != null) {
            futures.forEach(it -> it.complete(result));
        }
    }

    public int size() {
        return waiters.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }
}
