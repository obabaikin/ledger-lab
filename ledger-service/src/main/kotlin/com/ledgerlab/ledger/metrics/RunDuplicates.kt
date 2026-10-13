package com.ledgerlab.ledger.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ConfigurationProperties("ledger.run-duplicates")
data class RunDuplicatesProperties(val idleRetention: Duration, val sweepInterval: Duration)

/**
 * Redeliveries the ledger skipped, per run: not in any event, so only monitoring sees them (ADR 0015).
 * A lower bound: what a killed instance counted is gone with it.
 */
@Component
class RunDuplicates(private val registry: MeterRegistry, private val props: RunDuplicatesProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val counters = ConcurrentHashMap<UUID, Tracked>()

    private class Tracked(val counter: Counter, val lastNanos: Long)

    /** Never fails the caller: the transfer is already handled, a lost count only lowers a lower bound. */
    fun record(runId: UUID) {
        runCatching {
            // Under the run's entry, so a sweep cannot remove the counter between its lookup and its increment.
            counters.compute(runId) { _, tracked ->
                val counter = tracked?.counter ?: Counter.builder(METRIC).tag(RUN_TAG, runId.toString()).register(registry)
                counter.increment()
                Tracked(counter, System.nanoTime())
            }
        }.onFailure { log.warn("Duplicate of run {} not counted", runId, it) }
    }

    @Scheduled(fixedDelayString = "\${ledger.run-duplicates.sweep-interval}")
    fun removeIdle() {
        val now = System.nanoTime()
        for (runId in counters.keys) {
            counters.computeIfPresent(runId) { _, tracked ->
                if (now - tracked.lastNanos < props.idleRetention.toNanos()) return@computeIfPresent tracked
                registry.remove(tracked.counter)
                null
            }
        }
    }

    companion object {
        const val METRIC = "ledger.duplicates"
        const val RUN_TAG = "run"
    }
}
