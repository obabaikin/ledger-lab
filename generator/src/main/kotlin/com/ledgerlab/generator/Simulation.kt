package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.TransferStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
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
    val rejectedByReason: Map<RejectReason, Long>,
    val failed: Long,
    /** Sent, but no answer settled it: the gateway may have the transfer. */
    val unknown: Long,
    val retries: Long,
    /** Second sends of the same transfer, as a user pressing twice; not new transfers. */
    val duplicatesSent: Long,
    val inFlight: Long,
    val actualTps: Double,
    @field:Schema(description = "p50 of the time a waiting client's first press took to a final status, ms; presses whose wait ran out are left out; null before one.")
    val waitedLatencyP50Ms: Double?,
    @field:Schema(description = "p99, as waitedLatencyP50Ms.")
    val waitedLatencyP99Ms: Double?,
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
    val stopGrace: Duration,
    private val client: GatewayClient,
    maxInFlight: Int,
    registry: MeterRegistry,
    private val duplicateShare: Double = 0.0,
    private val duplicateDelays: List<Duration> = emptyList(),
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
    private val rejectedByReason = ConcurrentHashMap<RejectReason, LongAdder>()
    private val failed = LongAdder()
    private val unknown = LongAdder()
    private val retries = LongAdder()
    private val duplicatesSent = LongAdder()
    private val open = AtomicLong()
    /** How long a user waited for a final answer to one press, retries and their pauses included. */
    private val submissionLatency = listOf(SendKind.FIRST, SendKind.DUPLICATE).associateWith { kind ->
        Timer.builder("generator.submission.latency").tag("waited", "true").tag("kind", kind.tag)
            .publishPercentiles(*LATENCY_PERCENTILES).register(registry)
    }
    /** The same first presses as [submissionLatency], kept whole, so the run's report gets exact percentiles. */
    private val waitedLatenciesNanos = ConcurrentLinkedQueue<Long>()
    /** One point per answered send; a histogram, so percentiles can be taken over any mix of tags. */
    private val sendLatency = buildMap {
        for (waited in listOf(true, false)) for (kind in SendKind.entries) for (final in listOf(true, false)) {
            put(
                SendTimerKey(waited, kind, final),
                Timer.builder("generator.send.latency")
                    .tag("waited", waited.toString()).tag("kind", kind.tag).tag("final", final.toString())
                    .publishPercentileHistogram().register(registry),
            )
        }
    }
    /** Completed by a stop, so that duplicates still waiting for their moment are not sent. */
    private val stopSignal = Job()

    lateinit var startedAt: Instant
        private set
    private var startNanos = 0L
    /** Created at start; released some time after the run ends, while the status stays. Dropping the reference is what frees the memory. */
    @Volatile var journal: Journal? = null
        private set
    val journalCapacity: Long = journalCapacity(targetTps, duration)
    @Volatile var finishedAt: Instant? = null
    @Volatile var state: SimulationState = SimulationState.RUNNING
        private set

    @Volatile private var pacer: Job? = null

    private data class SendTimerKey(val waited: Boolean, val kind: SendKind, val final: Boolean)

    private class Answer(val result: SubmissionResult, val endedNanos: Long)

    init {
        require(duplicateShare == 0.0 || duplicateDelays.isNotEmpty()) { "duplicates need at least one delay" }
    }

    companion object {
        fun journalCapacity(targetTps: Int, duration: Duration): Long = targetTps.toLong() * duration.toSeconds()

        private val PACER_TICK = Duration.ofMillis(1)
        /** A send that ended without an answer, possibly after the request went out: it cannot count as failed. */
        private val CUT_SHORT = SubmissionResult(null, null, FIRST_ATTEMPT, SubmissionOutcome.UNKNOWN, Duration.ZERO)
        private val DRAIN_POLL = Duration.ofMillis(10)
        private val NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1)
        private val NANOS_PER_MILLI = TimeUnit.MILLISECONDS.toNanos(1).toDouble()
        private const val P50 = 0.5
        private const val P99 = 0.99
        /** Same percentiles as the bank's services publish, so dashboards can put them side by side. */
        private val LATENCY_PERCENTILES = doubleArrayOf(0.5, 0.95, 0.99)
    }

    /** The run's clock starts here, not at construction, so the first tick has no backlog to catch up. */
    fun start() {
        startedAt = Instant.now()
        startNanos = System.nanoTime()
        journal = Journal(startNanos)
        pacer = scope.launch {
            val intervalNanos = NANOS_PER_SECOND / targetTps
            val endNanos = startNanos + duration.toNanos()
            var launched = 0L
            // The interval is rounded down, so without the capacity check one more could be due before the end.
            while (isActive && System.nanoTime() < endNanos && launched < journalCapacity) {
                val due = minOf((System.nanoTime() - startNanos) / intervalNanos + 1, journalCapacity)
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
        stopSignal.complete()
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
        val transfer = profile.next()
        val entry = checkNotNull(journal) { "journal released while the run is active" }.add(transfer)
        // Counted only once the entry exists, so that every request ends up in exactly one outcome.
        requested.increment()
        val random = ThreadLocalRandom.current()
        val wait = if (random.nextDouble() < waitShare) waitTimeout else null
        val duplicateDelay = if (random.nextDouble() < duplicateShare) duplicateDelays[random.nextInt(duplicateDelays.size)] else null
        // Collected as each send ends, so a 2xx already received still counts when the other send is cut short.
        val answers = ConcurrentLinkedQueue<Answer>()
        try {
            coroutineScope {
                duplicateDelay?.let { launch { sendDuplicate(transfer, wait, entry, it)?.let(answers::add) } }
                val main = client.submit(transfer, wait, { state == SimulationState.RUNNING }, recorder(entry, wait != null, duplicate = false))
                answers.add(Answer(main, System.nanoTime()))
                retries.add((main.attempts - FIRST_ATTEMPT).toLong())
                recordSubmissionLatency(main, wait, SendKind.FIRST)
            }
        } catch (e: CancellationException) {
            settle(entry, combine(inOrder(answers) + CUT_SHORT))
            throw e
        } catch (e: Exception) {
            log.warn("Submission in simulation {} ended with an error", runId, e)
            settle(entry, combine(inOrder(answers) + CUT_SHORT))
            return
        }
        settle(entry, combine(inOrder(answers)))
    }

    private fun inOrder(answers: Collection<Answer>): List<SubmissionResult> =
        answers.sortedBy { it.endedNanos }.map { it.result }

    private suspend fun sendDuplicate(transfer: PlannedTransfer, wait: Duration?, entry: JournalEntry, delay: Duration): Answer? {
        val stopped = withTimeoutOrNull(delay.toMillis()) { stopSignal.join() } != null
        if (stopped || state != SimulationState.RUNNING) return null
        duplicatesSent.increment()
        val result = try {
            client.submit(transfer, wait, { false }, recorder(entry, wait != null, duplicate = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept to this send, so it cannot cancel the first send and lose an answer that one gets.
            log.warn("Duplicate in simulation {} ended with an error", runId, e)
            CUT_SHORT
        }
        recordSubmissionLatency(result, wait, SendKind.DUPLICATE)
        return Answer(result, System.nanoTime())
    }

    private fun recorder(entry: JournalEntry, waited: Boolean, duplicate: Boolean) =
        SendRecorder { attempt, startedNanos, endedNanos, result, httpStatus ->
            val kind = when {
                duplicate -> SendKind.DUPLICATE
                attempt == FIRST_ATTEMPT -> SendKind.FIRST
                else -> SendKind.RETRY
            }
            entry.record(kind, startedNanos, endedNanos, result, httpStatus)
            if (httpStatus != null && HttpStatusCode.valueOf(httpStatus).is2xxSuccessful) {
                sendLatency.getValue(SendTimerKey(waited, kind, final = httpStatus == HttpStatus.OK.value()))
                    .record(endedNanos - startedNanos, TimeUnit.NANOSECONDS)
            }
        }

    private fun recordSubmissionLatency(result: SubmissionResult, wait: Duration?, kind: SendKind) {
        if (wait != null && result.outcome == SubmissionOutcome.ACKNOWLEDGED && result.status?.isFinal == true) {
            submissionLatency.getValue(kind).record(result.latency)
            if (kind == SendKind.FIRST) waitedLatenciesNanos.add(result.latency.toNanos())
        }
    }

    private fun settle(entry: JournalEntry, result: SubmissionResult) {
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
                    TransferStatus.POSTED -> posted.increment()
                    TransferStatus.REJECTED -> {
                        rejected.increment()
                        result.rejectReason?.let { rejectedByReason.computeIfAbsent(it) { LongAdder() }.increment() }
                    }
                    TransferStatus.ACCEPTED, TransferStatus.PUBLISHED, null -> {}
                }
                entry.settle(JournalOutcome.ACKNOWLEDGED, result.status, result.rejectReason)
            }
        }
    }

    fun finishedBefore(instant: Instant): Boolean = !state.active && finishedAt?.isBefore(instant) == true

    fun releaseJournal() {
        check(!state.active) { "journal of an active run cannot be released" }
        journal = null
    }

    fun status(): SimulationStatus {
        val end = finishedAt ?: Instant.now()
        val seconds = Duration.between(startedAt, end).toNanos().coerceAtLeast(1).toDouble() / NANOS_PER_SECOND
        return SimulationStatus(
            runId, state, targetTps, duration.toSeconds().toInt(), startedAt, finishedAt,
            requested.sum(), acknowledged.sum(), posted.sum(), rejected.sum(),
            rejectedByReason.mapValues { it.value.sum() }, failed.sum(), unknown.sum(), retries.sum(),
            duplicatesSent.sum(), open.get(), requested.sum() / seconds,
            waitedLatencyMs(P50), waitedLatencyMs(P99),
        )
    }

    private fun waitedLatencyMs(p: Double): Double? =
        percentileCont(waitedLatenciesNanos.sorted(), p)?.let { it / NANOS_PER_MILLI }
}

private val SendKind.tag: String get() = name.lowercase()

/** Linear interpolation between ranks, as PostgreSQL's percentile_cont, so client and bank percentiles compare. */
internal fun percentileCont(sorted: List<Long>, p: Double): Double? {
    if (sorted.isEmpty()) return null
    val rank = p * (sorted.size - 1)
    val lower = rank.toInt()
    val upper = minOf(lower + 1, sorted.size - 1)
    return sorted[lower] + (rank - lower) * (sorted[upper] - sorted[lower])
}

/**
 * The outcome of a transfer from all its sends, given in the order they ended (client contract,
 * ADR 0006): any 2xx means the gateway has it; otherwise a send that may have arrived leaves it
 * unknown; only then has it failed. A final status outranks an accepted one, a later answer an earlier one.
 */
internal fun combine(results: List<SubmissionResult>): SubmissionResult {
    val acknowledged = results.filter { it.outcome == SubmissionOutcome.ACKNOWLEDGED }
    if (acknowledged.isNotEmpty()) return acknowledged.lastOrNull { it.status?.isFinal == true } ?: acknowledged.last()
    return results.firstOrNull { it.outcome == SubmissionOutcome.UNKNOWN } ?: results.last()
}
