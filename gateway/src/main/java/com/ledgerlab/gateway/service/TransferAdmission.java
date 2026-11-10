package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Virtual threads put no limit on requests, so without these bounds a slow database or ledger fills the heap with
 * open requests. {@link #enter} bounds whole submissions, waiting for the result included, and refuses at once: a request
 * that waits for a place holds its memory all the same. {@link #admit} bounds the ones that insert, holding or waiting for a
 * database connection, and lets one wait briefly.
 */
@Component
public class TransferAdmission {

    private final Semaphore inFlight;
    private final Semaphore permits;
    private final long waitNanos;
    private final Counter refused;

    public TransferAdmission(GatewayProperties props, MeterRegistry registry) {
        this.inFlight = new Semaphore(props.inflight().maxConcurrent());
        this.permits = new Semaphore(props.admission().maxConcurrent());
        this.waitNanos = props.admission().waitLimit().toNanos();
        this.refused = Counter.builder("gateway.transfers").tag("outcome", "refused").register(registry);
    }

    public <T> T enter(InterruptibleSupplier<T> block) throws InterruptedException {
        if (!inFlight.tryAcquire()) {
            refused.increment();
            throw new GatewayBusyException();
        }
        try {
            return block.get();
        } finally {
            inFlight.release();
        }
    }

    public <T> T admit(InterruptibleSupplier<T> block) throws InterruptedException {
        if (!permits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
            refused.increment();
            throw new GatewayBusyException();
        }
        try {
            return block.get();
        } finally {
            permits.release();
        }
    }
}
