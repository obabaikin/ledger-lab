package com.ledgerlab.control.runs

import java.time.Duration
import java.time.Instant
import java.util.UUID

val QUOTA_WINDOW: Duration = Duration.ofHours(1)

enum class RunState(val isFinal: Boolean, val holdsSlot: Boolean) {
    QUEUED(isFinal = false, holdsSlot = false),
    CREATED(isFinal = false, holdsSlot = true),
    RUNNING(isFinal = false, holdsSlot = true),
    DRAINING(isFinal = false, holdsSlot = true),
    RECONCILED(isFinal = true, holdsSlot = false),
    FAILED(isFinal = true, holdsSlot = false),
    EXPIRED(isFinal = true, holdsSlot = false),
}

val UNFINISHED_STATES = RunState.entries.filterNot { it.isFinal }
val SLOT_STATES = RunState.entries.filter { it.holdsSlot }

enum class RunResult { BALANCED, BREAKS, INCOMPLETE }

data class Run(
    val runId: UUID,
    val sessionId: UUID,
    val requestId: UUID,
    val tps: Int,
    val durationSeconds: Int,
    val state: RunState,
    val createdAt: Instant,
    val admittedAt: Instant?,
    val finishedAt: Instant?,
    val position: Long?,
    val startSent: Boolean = false,
    val startedAt: Instant? = null,
    val stopRequested: Boolean = false,
    val clientLost: Boolean = false,
    val drainStartedAt: Instant? = null,
    val closedAt: Instant? = null,
    val drainProgressAt: Instant? = null,
    val booksFinalAt: Instant? = null,
    val result: RunResult? = null,
    val failure: String? = null,
)

data class RunRequest(val requestId: UUID?, val tps: Int?, val durationSeconds: Int?)

data class QuotaUsage(val used: Long, val retryAfterSeconds: Long?)

class RunNotFoundException : RuntimeException()

class InvalidRunRequestException(message: String) : RuntimeException(message)

class RequestIdConflictException : RuntimeException()

class RunNotFinalException : RuntimeException()

class QuotaExceededException(val retryAfterSeconds: Long?) : RuntimeException()

class QueueFullException : RuntimeException()

class StartsBusyException : RuntimeException()

class RunNotStartedException : RuntimeException()

class ReportNotReadyException : RuntimeException()
