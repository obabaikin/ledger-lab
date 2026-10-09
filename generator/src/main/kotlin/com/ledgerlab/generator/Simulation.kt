package com.ledgerlab.generator

import com.ledgerlab.common.Observability.LATENCY_PERCENTILES
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.cancellation.CancellationException

enum class SimulationState(val active: Boolean) {
    RUNNING(true),
    /** No new submissions or retries; open requests get [Simulation.stopGrace] to finish. */
    STOPPING(true),
    FINISHED(false),
    STOPPED(false),
}

data class SimulationStatus(
    val runId: UUID,
    val state: SimulationState,
    val targetTps: Int,
    val durationSeconds: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val requested: Long,
    val acknowledged: Long,
    val posted: Long,
    val rejected: Long,
    val rejectedByReason: Map<String, Long>,
    val failed: Long,
    /** Sent, but no answer settled it: the gateway may have the transfer. */
    val unknown: Long,
    val retries: Long,
    val inFlight: Long,
    val actualTps: Double,
)

/**
 * One load run. A pacer launches [targetTps] submissions per second, spread evenly; each
 * submission is a coroutine over a non-blocking HTTP call, so slow responses do not slow the
 * pace. The semaphore caps how many requests may be open at once.
 */
class Simulation(
    val runId: UUID,
    private val targetTps: Int,
    private val duration: Duration,
    private val profile: LoadProfile,
    /** Share of submissions that wait for the ledger's result, to measure end-to-end latency. */
    private val waitShare: Double,
    private val waitTimeout: Duration,
    /** How long a stop waits for open requests before cancelling them. */
    val stopGrace: Duration,
    private val client: GatewayClient,
    maxInFlight: Int,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Submissions are not children of the pacer, so stopping the pacer leaves open requests running.
    private val submissions = SupervisorJob(scope.coroutineContext.job)
    private val inFlight = Semaphore(maxInFlight)

    private val requested = LongAdder()
    private val acknowledged = LongAdder()
    private val posted = LongAdder()
    private val rejected = LongAdder()
    private val rejectedByReason = ConcurrentHashMap<String, LongAdder>()
    private val failed = LongAdder()
    private val unknown = LongAdder()
    private val retries = LongAdder()
    private val open = AtomicLong()
    private val latency = Timer.builder("generator.submission.latency").tag("waited", "true")
        .publishPercentiles(*LATENCY_PERCENTILES).register(registry)

    val startedAt: Instant = Instant.now()
    private val startNanos = System.nanoTime()
    /** Released some time after the run ends, while the status stays; dropping the reference is what frees the memory. */
    @Volatile var journal: Journal? = Journal(startNanos)
        private set
    /** Journal entries this run may take; reserved against the generator-wide cap at start. */
    val journalCapacity: Long = targetTps.toLong() * duration.toSeconds()
    @Volatile var finishedAt: Instant? = null
    @Volatile var state: SimulationState = SimulationState.RUNNING
        private set

    @Volatile private var pacer: Job? = null

    private companion object {
        /** How often the pacer checks whether more submissions are due. */
        val PACER_TICK = Duration.ofMillis(1)
        /** How often the pacer checks for the last open requests after the run time is over. */
        val DRAIN_POLL = Duration.ofMillis(10)
    }

    fun start() {
        pacer = scope.launch {
            val intervalNanos = 1_000_000_000L / targetTps
            val endNanos = startNanos + duration.toNanos()
            var launched = 0L
            while (isActive && System.nanoTime() < endNanos) {
                val due = (System.nanoTime() - startNanos) / intervalNanos + 1
                while (launched < due && System.nanoTime() < endNanos) {
                    // acquire() does not check for cancellation when a permit is free.
                    ensureActive()
                    launched++
                    inFlight.acquire()
                    open.incrementAndGet()
                    // A completion handler also runs for a coroutine cancelled before it started, unlike finally.
                    scope.launch(submissions) { submitOne() }.invokeOnCompletion {
                        open.decrementAndGet()
                        inFlight.release()
                    }
                }
                delay(PACER_TICK.toMillis())
            }
            while (open.get() > 0 && isActive) delay(DRAIN_POLL.toMillis())
            transition(SimulationState.RUNNING, SimulationState.FINISHED)
        }
    }

    /**
     * Stops the client side only: no new submissions, no retries. Requests already sent get
     * [stopGrace] to receive their answer; whatever is left after that is cancelled and counted
     * as unknown.
     */
    fun stop() {
        if (!transition(SimulationState.RUNNING, SimulationState.STOPPING)) return
        scope.launch {
            // Until the pacer has finished, it may still be counting a submission it was launching.
            pacer?.cancelAndJoin()
            val drained = withTimeoutOrNull(stopGrace.toMillis()) {
                while (open.get() > 0) delay(DRAIN_POLL.toMillis())
            } != null
            if (!drained) submissions.cancelAndJoin()
            transition(SimulationState.STOPPING, SimulationState.STOPPED)
        }
    }

    @Synchronized
    private fun transition(from: SimulationState, to: SimulationState): Boolean {
        if (state != from) return false
        state = to
        if (!to.active) {
            finishedAt = Instant.now()
            log.info(
                "Simulation {} {}: requested={} acknowledged={} failed={} unknown={}",
                runId, to, requested.sum(), acknowledged.sum(), failed.sum(), unknown.sum(),
            )
        }
        return true
    }

    private suspend fun submitOne() {
        // A submission launched just before a stop may start after it; it must not send.
        if (state != SimulationState.RUNNING) return
        val command = profile.next()
        val entry = checkNotNull(journal) { "journal released while the run is active" }.add(command)
        // Counted only once the entry exists, so that every request ends up in exactly one outcome.
        requested.increment()
        try {
            val wait = if (ThreadLocalRandom.current().nextDouble() < waitShare) waitTimeout else null
            val result = client.submit(command, wait, { state == SimulationState.RUNNING }, entry)
            retries.add((result.attempts - 1).toLong())
            when (result.outcome) {
                SubmissionOutcome.FAILED -> {
                    failed.increment()
                    entry.settle(JournalOutcome.FAILED)
                }
                SubmissionOutcome.UNKNOWN -> {
                    unknown.increment()
                    entry.settle(JournalOutcome.UNKNOWN)
                }
                SubmissionOutcome.ACKNOWLEDGED -> {
                    acknowledged.increment()
                    when (result.status) {
                        GatewayApi.STATUS_POSTED -> posted.increment()
                        GatewayApi.STATUS_REJECTED -> {
                            rejected.increment()
                            result.rejectReason?.let { rejectedByReason.computeIfAbsent(it) { LongAdder() }.increment() }
                        }
                    }
                    entry.settle(JournalOutcome.ACKNOWLEDGED, result.status, result.rejectReason)
                    if (wait != null && result.status in GatewayApi.FINAL_STATUSES) latency.record(result.latency)
                }
            }
        } catch (e: CancellationException) {
            unknown.increment()
            entry.settle(JournalOutcome.UNKNOWN)
            throw e
        } catch (e: Exception) {
            // It may have been thrown after the request went out, so it cannot count as failed.
            log.warn("Submission in simulation {} ended with an error", runId, e)
            unknown.increment()
            entry.settle(JournalOutcome.UNKNOWN)
        }
    }

    fun releaseJournal() {
        check(!state.active) { "journal of an active run cannot be released" }
        journal = null
    }

    fun status(): SimulationStatus {
        val end = finishedAt ?: Instant.now()
        val seconds = Duration.between(startedAt, end).toMillis().coerceAtLeast(1) / 1000.0
        return SimulationStatus(
            runId, state, targetTps, duration.toSeconds().toInt(), startedAt, finishedAt,
            requested.sum(), acknowledged.sum(), posted.sum(), rejected.sum(),
            rejectedByReason.mapValues { it.value.sum() }, failed.sum(), unknown.sum(), retries.sum(),
            open.get(), requested.sum() / seconds,
        )
    }
}
