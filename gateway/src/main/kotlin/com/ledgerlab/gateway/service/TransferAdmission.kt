package com.ledgerlab.gateway.service

import com.ledgerlab.gateway.GatewayProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class GatewayBusyException : RuntimeException("Too many transfers in progress")

/**
 * Bounds the submissions that insert, holding or waiting for a database connection; reading the result for a
 * waiting client is not bounded. Virtual threads put no limit on requests, so without it a slow database fills the
 * heap with requests waiting for the pool.
 */
@Component
class TransferAdmission(props: GatewayProperties, registry: MeterRegistry) {
    private val permits = Semaphore(props.admission.maxConcurrent)
    private val waitNanos = props.admission.wait.toNanos()
    private val refused = Counter.builder("gateway.transfers").tag("outcome", "refused").register(registry)

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
