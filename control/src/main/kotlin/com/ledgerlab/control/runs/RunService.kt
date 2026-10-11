package com.ledgerlab.control.runs

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.api.ControlErrors
import com.ledgerlab.control.entry.InviteNotValidException
import com.ledgerlab.control.entry.InviteRepository
import com.ledgerlab.control.entry.SessionNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RunService(
    private val invites: InviteRepository,
    private val runs: RunRepository,
    private val props: ControlProperties,
) {
    @Transactional
    fun start(sessionId: UUID, request: RunRequest): Run {
        val session = invites.findSession(sessionId) ?: throw SessionNotFoundException()
        val requestId = request.requestId ?: throw InvalidRunRequestException(ControlErrors.REQUEST_ID_REQUIRED)
        runs.lockQueue()
        runs.findByRequest(sessionId, requestId)?.let { existing ->
            if (existing.tps != request.tps || existing.durationSeconds != request.durationSeconds) throw RequestIdConflictException()
            return existing
        }
        if (request.tps !in props.runs.tpsOptions) {
            throw InvalidRunRequestException(ControlErrors.tpsNotAllowed(props.runs.tpsOptions))
        }
        if (request.durationSeconds !in props.runs.durationSecondsOptions) {
            throw InvalidRunRequestException(ControlErrors.durationNotAllowed(props.runs.durationSecondsOptions))
        }
        val invite = invites.find(session.token)?.takeIf { it.valid } ?: throw InviteNotValidException()
        val usage = runs.quotaUsage(session.token, QUOTA_WINDOW, invite.quota)
        if (usage.used >= invite.quota) throw QuotaExceededException(usage.retryAfterSeconds)
        if (runs.countIn(listOf(RunState.QUEUED)) >= props.runs.maxQueued) throw QueueFullException()

        val runId = runs.insertQueued(sessionId, session.token, requestId, request)
        return checkNotNull(runs.find(sessionId, runId))
    }

    @Transactional(readOnly = true)
    fun find(sessionId: UUID, runId: UUID): Run {
        invites.findSession(sessionId) ?: throw SessionNotFoundException()
        return runs.find(sessionId, runId) ?: throw RunNotFoundException()
    }

    /** The answer's run is RUNNING when the stop was taken; any later state means the run had already ended. */
    @Transactional
    fun stop(sessionId: UUID, runId: UUID): Run {
        invites.findSession(sessionId) ?: throw SessionNotFoundException()
        val run = runs.findForUpdate(sessionId, runId) ?: throw RunNotFoundException()
        when (run.state) {
            RunState.QUEUED, RunState.CREATED -> throw RunNotStartedException()
            RunState.RUNNING -> runs.requestStop(runId)
            else -> Unit
        }
        return checkNotNull(runs.find(sessionId, runId))
    }

    @Transactional(readOnly = true)
    fun report(sessionId: UUID, runId: UUID): RunReport {
        val run = find(sessionId, runId)
        if (run.state != RunState.RECONCILED) throw ReportNotReadyException()
        return checkNotNull(runs.reportOf(runId))
    }

    @Transactional(readOnly = true)
    fun listOf(sessionId: UUID): List<Run> {
        invites.findSession(sessionId) ?: throw SessionNotFoundException()
        return runs.listOf(sessionId)
    }
}
