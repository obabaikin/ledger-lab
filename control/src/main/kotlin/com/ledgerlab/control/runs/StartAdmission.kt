package com.ledgerlab.control.runs

import com.ledgerlab.control.ControlProperties
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bounds the starts that hold a database connection while they wait for the queue's lock, so a burst of starts
 * cannot take the whole pool from the reads of the page and from the conductor's tick.
 */
@Component
class StartAdmission(props: ControlProperties) {
    private val permits = Semaphore(props.runs.maxConcurrentStarts)
    private val waitNanos = props.runs.startWait.toNanos()

    fun <T> admit(block: () -> T): T {
        if (!permits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) throw StartsBusyException()
        try {
            return block()
        } finally {
            permits.release()
        }
    }
}
