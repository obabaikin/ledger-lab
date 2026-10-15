package com.ledgerlab.control.runs

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.chaos.ChaosRepository
import com.ledgerlab.control.gateway.GatewayClient
import com.ledgerlab.control.generator.GeneratorClient
import com.ledgerlab.control.generator.SimulationStatus
import com.ledgerlab.control.generator.StartSimulationRequest
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.isTransient
import com.ledgerlab.control.ledger.LedgerClient
import com.ledgerlab.control.ledger.OpenAccountsRequest
import com.ledgerlab.control.ledger.ReconciliationReport
import com.ledgerlab.control.gateway.RunSummary
import com.ledgerlab.control.metrics.LiveRuns
import com.ledgerlab.control.reporting.ReportingClient
import com.ledgerlab.control.reporting.RunStats
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@Service
class Conductor(
    private val runs: RunRepository,
    private val ledger: LedgerClient,
    private val gateway: GatewayClient,
    private val generator: GeneratorClient,
    private val reporting: ReportingClient,
    private val liveRuns: LiveRuns,
    private val chaos: ChaosRepository,
    private val props: ControlProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The last answers to a run's setup, for the log line if the setup times out; lost on a restart, as only the log needs them. */
    private val setupReplies = ConcurrentHashMap<UUID, String>()

    /** Runs step side by side, so a slow call of one run does not eat into another run's deadline. */
    fun tick() {
        val inProgress = runs.inProgress()
        Executors.newVirtualThreadPerTaskExecutor().use { steps ->
            steps.submit { refreshLive(inProgress) }
            inProgress.forEach { run -> steps.submit { step(run) } }
        }
    }

    private fun refreshLive(inProgress: List<Run>) {
        try {
            liveRuns.refresh(inProgress.filter { it.state in LIVE_STATES }.map { it.runId })
        } catch (e: Exception) {
            log.error("Live numbers not read, retried on the next tick", e)
        }
    }

    private fun step(run: Run) {
        try {
            when (run.state) {
                RunState.CREATED -> setUp(run, runs.now())
                RunState.RUNNING -> watch(run, runs.now())
                RunState.DRAINING -> drain(run, runs.now())
                else -> Unit
            }
        } catch (e: RuntimeException) {
            log.error("Run {}: step in {} failed, retried on the next tick", run.runId, run.state, e)
        }
    }

    private fun setUp(run: Run, now: Instant) {
        val status = generator.status(run.runId)
        // A start that reached the generator before a restart of control must not be sent again: it would replay the run.
        if (status is Reply.Answered) return started(run)
        val timedOut = now >= checkNotNull(run.admittedAt) + props.runs.setupTimeout
        if (timedOut) {
            val earlier = setupReplies[run.runId] ?: "ledger not asked since control started"
            return failed(run, RunOutcomes.SETUP_TIMED_OUT, "generator status: ${status.summary()}; $earlier")
        }
        if (status !is Reply.Refused || status.status != HttpStatus.NOT_FOUND.value()) return
        // A start that may have arrived but is unknown now means the generator restarted: the run goes on as a lost client.
        if (run.startSent) return started(run)

        val accounts = props.runs.accounts
        val opened = ledger.openAccounts(
            run.runId,
            OpenAccountsRequest(accounts.count, accounts.openingBalanceCents, accounts.blocked, accounts.dailyLimitCents),
        )
        setupReplies[run.runId] = "ledger accounts: ${opened.summary()}"
        val alreadyOpened = opened is Reply.Refused && opened.status == HttpStatus.CONFLICT.value()
        if (opened is Reply.Refused && opened.status == HttpStatus.BAD_REQUEST.value()) return failed(run, RunOutcomes.SETUP_REJECTED)
        if (opened !is Reply.Answered && !alreadyOpened) return

        runs.markStartSent(run.runId, sent = true)
        val start = generator.start(
            StartSimulationRequest(run.runId, run.tps, run.durationSeconds, accounts.count, accounts.blocked),
        )
        setupReplies[run.runId] = "ledger accounts: ${opened.summary()}; generator start: ${start.summary()}"
        when {
            start is Reply.Answered -> started(run)
            start is Reply.Refused && start.status == HttpStatus.BAD_REQUEST.value() -> failed(run, RunOutcomes.SETUP_REJECTED)
            start is Reply.NotConnected || (start is Reply.Refused && !start.isTransient) -> runs.markStartSent(run.runId, sent = false)
            else -> Unit
        }
    }

    private fun started(run: Run) {
        setupReplies.remove(run.runId)
        if (runs.markStarted(run.runId) == 1) log.info("Run {}: started", run.runId)
    }

    private fun failed(run: Run, failure: String, replies: String? = null) {
        setupReplies.remove(run.runId)
        if (runs.markFailed(run.runId, run.state, failure) == 1) {
            log.warn("Run {}: failed in {}: {}{}", run.runId, run.state, failure, replies?.let { " ($it)" }.orEmpty())
        }
    }

    private fun Reply<*>.summary(): String = when (this) {
        is Reply.Answered -> "answered $status"
        is Reply.Refused -> "$status $error"
        else -> toString()
    }

    private fun watch(run: Run, now: Instant) {
        if (run.stopRequested) generator.stop(run.runId)
        val status = generator.status(run.runId)
        val clientLost = status is Reply.Refused && status.status == HttpStatus.NOT_FOUND.value()
        val clientDone = status is Reply.Answered && !(status.body as SimulationStatus).state.active
        val overdue = now >= checkNotNull(run.startedAt) + Duration.ofSeconds(run.durationSeconds.toLong()) + props.runs.runningGrace
        if (overdue && !clientDone && !clientLost) generator.stop(run.runId)
        if (clientDone || clientLost || overdue) {
            if (runs.startDraining(run.runId, clientLost) == 1) log.info("Run {}: draining{}", run.runId, if (clientLost) ", client lost" else "")
        }
    }

    private fun drain(run: Run, now: Instant) {
        val deadline = checkNotNull(run.drainStartedAt) + props.runs.drainTimeout
        val pastDeadline = now >= deadline
        if (run.closedAt == null) {
            when (val closed = gateway.close(run.runId)) {
                is Reply.Answered -> runs.markClosed(run.runId, checkNotNull(closed.body).closedAt)
                else -> if (pastDeadline) failed(run, RunOutcomes.PERIOD_NOT_CLOSED)
            }
            return
        }
        val summary = gateway.summary(run.runId)
        val books = ledger.reconciliation(run.runId)
        if (summary !is Reply.Answered || books !is Reply.Answered) {
            if (pastDeadline) failed(run, RunOutcomes.BANK_UNAVAILABLE, "gateway: ${summary.summary()}, ledger: ${books.summary()}")
            return
        }
        val bank = checkNotNull(summary.body)
        val ledgerReport = checkNotNull(books.body)
        // Both counts only grow after the close, so their sum grows while either side still moves.
        val progress = ledgerReport.postedTransfers + ledgerReport.rejectedTransfers + bank.posted + bank.rejected
        val progressAt = if (runs.recordDrainProgress(run.runId, progress) == 1) now else checkNotNull(run.drainProgressAt)
        val drained = drainFinished(bank, ledgerReport)
        if (drained && run.booksFinalAt == null) runs.markBooksFinal(run.runId)
        val stalled = now >= progressAt + props.runs.drainStallTimeout
        if (!drained && !overcounted(bank, ledgerReport) && !stalled && !pastDeadline) return

        val stats = (reporting.stats(run.runId) as? Reply.Answered)?.body
        val catchingUp = drained && !pastDeadline && now < progressAt + props.reporting.catchUpWait
        if (catchingUp && (stats == null || !projectionMatchesBooks(stats, bank, ledgerReport))) return

        val client = clientSide(run, pastDeadline) ?: return
        reconciled(run, bank, ledgerReport, client, stats)
    }

    /** Null while the client's reconciliation may still come: the generator answered 503 or not at all. */
    private fun clientSide(run: Run, pastDeadline: Boolean): ClientSide? {
        if (run.clientLost) return ClientSide.Missing(RunOutcomes.CLIENT_LOST)
        val reply = generator.reconciliation(run.runId)
        return when {
            reply is Reply.Answered -> ClientSide.Reconciled(checkNotNull(reply.body))
            reply is Reply.Refused && (reply.status in CLIENT_RECONCILIATION_FINAL || !reply.isTransient) ->
                ClientSide.Missing(RunOutcomes.clientReconciliationFailed(reply.error))
            pastDeadline -> ClientSide.Missing(RunOutcomes.CLIENT_RECONCILIATION_UNAVAILABLE)
            else -> null
        }
    }

    private fun reconciled(run: Run, bank: RunSummary, ledgerReport: ReconciliationReport, client: ClientSide, stats: RunStats?) {
        val simulation = (generator.status(run.runId) as? Reply.Answered)?.body
        val measurements = RunMeasurements(
            clientLatencyP50Ms = simulation?.waitedLatencyP50Ms,
            clientLatencyP99Ms = simulation?.waitedLatencyP99Ms,
            stats = stats,
            ledgerDuplicatesAtLeast = liveRuns.ledgerDuplicatesAtLeast(run.runId),
            chaos = chaosSince(run),
        )
        val report = verdictOf(bank, ledgerReport, client, measurements)
        if (runs.markReconciled(run.runId, report) == 1) {
            log.info("Run {}: reconciled, {} {}", run.runId, report.result, report.reasons)
        }
    }

    /** From the admission: a crash while the run is set up also hits it. */
    fun chaosSince(run: Run): List<ChaosInRun> {
        val admittedAt = run.admittedAt ?: run.createdAt
        return chaos.since(admittedAt).map { it.inRun(run.startedAt ?: admittedAt) }
    }

    private companion object {
        /** A 502 means the generator could not trust the gateway's statement: reported with its words, not waited on. */
        val CLIENT_RECONCILIATION_FINAL = setOf(HttpStatus.BAD_GATEWAY.value())
        val LIVE_STATES = setOf(RunState.RUNNING, RunState.DRAINING)
    }
}

@Component
class ConductorScheduler(private val conductor: Conductor) {

    @Scheduled(fixedDelayString = "\${control.queue.poll-interval}", initialDelayString = "\${control.queue.poll-interval}")
    fun tick() = conductor.tick()
}
