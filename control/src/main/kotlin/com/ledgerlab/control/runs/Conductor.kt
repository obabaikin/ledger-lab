package com.ledgerlab.control.runs

import com.ledgerlab.control.ControlProperties
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
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class Conductor(
    private val runs: RunRepository,
    private val ledger: LedgerClient,
    private val gateway: GatewayClient,
    private val generator: GeneratorClient,
    private val props: ControlProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun tick() {
        for (run in runs.inProgress()) {
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
    }

    private fun setUp(run: Run, now: Instant) {
        val status = generator.status(run.runId)
        // A start that reached the generator before a restart of control must not be sent again: it would replay the run.
        if (status is Reply.Answered) return started(run)
        val timedOut = now >= checkNotNull(run.admittedAt) + props.runs.setupTimeout
        if (timedOut) return failed(run, RunOutcomes.SETUP_TIMED_OUT)
        if (status !is Reply.Refused || status.status != HttpStatus.NOT_FOUND.value()) return

        val accounts = props.runs.accounts
        val opened = ledger.openAccounts(
            run.runId,
            OpenAccountsRequest(accounts.count, accounts.openingBalanceCents, accounts.blocked, accounts.dailyLimitCents),
        )
        val alreadyOpened = opened is Reply.Refused && opened.status == HttpStatus.CONFLICT.value()
        if (opened is Reply.Refused && opened.status == HttpStatus.BAD_REQUEST.value()) return failed(run, RunOutcomes.SETUP_REJECTED)
        if (opened !is Reply.Answered && !alreadyOpened) return

        val start = generator.start(
            StartSimulationRequest(run.runId, run.tps, run.durationSeconds, accounts.count, accounts.blocked),
        )
        when {
            start is Reply.Answered -> started(run)
            start is Reply.Refused && start.status == HttpStatus.BAD_REQUEST.value() -> failed(run, RunOutcomes.SETUP_REJECTED)
            else -> Unit
        }
    }

    private fun started(run: Run) {
        if (runs.markStarted(run.runId) == 1) log.info("Run {}: started", run.runId)
    }

    private fun failed(run: Run, failure: String) {
        if (runs.markFailed(run.runId, run.state, failure) == 1) log.warn("Run {}: failed in {}: {}", run.runId, run.state, failure)
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
            if (pastDeadline) failed(run, RunOutcomes.BANK_UNAVAILABLE)
            return
        }
        val bank = checkNotNull(summary.body)
        val ledgerReport = checkNotNull(books.body)
        val applied = ledgerReport.postedTransfers + ledgerReport.rejectedTransfers
        val progressAt = if (runs.recordDrainProgress(run.runId, applied) == 1) now else checkNotNull(run.drainProgressAt)
        val drained = drainFinished(bank, ledgerReport)
        val stalled = now >= progressAt + props.runs.drainStallTimeout
        if (!drained && !stalled && !pastDeadline) return

        val client = clientSide(run, pastDeadline) ?: return
        reconciled(run, bank, ledgerReport, client)
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

    private fun reconciled(run: Run, bank: RunSummary, ledgerReport: ReconciliationReport, client: ClientSide) {
        val report = verdictOf(bank, ledgerReport, client)
        if (runs.markReconciled(run.runId, report) == 1) {
            log.info("Run {}: reconciled, {} {}", run.runId, report.result, report.reasons)
        }
    }

    private companion object {
        /** A 502 is the generator finding the statement inconsistent: a repeat reads the same statement. */
        val CLIENT_RECONCILIATION_FINAL = setOf(HttpStatus.BAD_GATEWAY.value())
    }
}

@Component
class ConductorScheduler(private val conductor: Conductor) {

    @Scheduled(fixedDelayString = "\${control.queue.poll-interval}", initialDelayString = "\${control.queue.poll-interval}")
    fun tick() = conductor.tick()
}
