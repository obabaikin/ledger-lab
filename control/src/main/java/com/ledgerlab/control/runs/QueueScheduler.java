package com.ledgerlab.control.runs;

import com.ledgerlab.control.gateway.GatewayWarmUp;
import com.ledgerlab.control.ledger.LedgerReadiness;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QueueScheduler {

    private final LedgerReadiness ledger;
    private final GatewayWarmUp gateway;
    private final RunQueue queue;
    private volatile boolean lastReady = false;

    public QueueScheduler(LedgerReadiness ledger, GatewayWarmUp gateway, RunQueue queue) {
        this.ledger = ledger;
        this.gateway = gateway;
        this.queue = queue;
    }

    /** The last tick's answer, for the runs' views between ticks; not ready until the first one. */
    public boolean bankReady() {
        return lastReady;
    }

    // The readiness calls stay outside the queue's transaction, so a slow Prometheus never holds the lock.
    @Scheduled(fixedDelayString = "${control.queue.poll-interval}", initialDelayString = "${control.queue.poll-interval}")
    public QueueStep tick() throws InterruptedException {
        // Asked every tick, not only when the ledger is ready: the runs' views show what the queue waits for.
        var gatewayWarm = gateway.check();
        lastReady = ledger.isReady() && gatewayWarm;
        return queue.advance(lastReady);
    }
}
