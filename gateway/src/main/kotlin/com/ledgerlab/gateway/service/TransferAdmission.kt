package com.ledgerlab.gateway.service

import com.ledgerlab.gateway.GatewayProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class GatewayBusyException : RuntimeException("Too many transfers in progress")

/**
 * Virtual threads put no limit on requests, so without these bounds a slow database or ledger fills the heap with
 * open requests. [enter] bounds whole submissions, waiting for the result included, and refuses at once: a request
 * that waits for a place holds its memory all the same. [admit] bounds the ones that insert, holding or waiting for a
 * database connection, and lets one wait briefly.
 */
@Component
class TransferAdmission(props: GatewayProperties, registry: MeterRegistry) {
    private val inFlight = Semaphore(props.inflight.maxConcurrent)
    private val permits = Semaphore(props.admission.maxConcurrent)
    private val waitNanos = props.admission.wait.toNanos()
    private val refused = Counter.builder("gateway.transfers").tag("outcome", "refused").register(registry)

    fun <T> enter(block: () -> T): T {
        if (!inFlight.tryAcquire()) {
            refused.increment()
            throw GatewayBusyException()
        }
        try {
            return block()
        } finally {
            inFlight.release()
        }
    }

    fun <T> admit(block: () -> T): T {
        if (!permits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
            refused.increment()
            throw GatewayBusyException()
        }
        try {
            return block()
        } finally {
            permits.release()
        }
    }
}
