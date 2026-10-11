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
)

data class RunRequest(val requestId: UUID, val tps: Int?, val durationSeconds: Int?)

data class QuotaUsage(val used: Long, val retryAfterSeconds: Long?)

class RunNotFoundException : RuntimeException()

class InvalidRunOptionsException(message: String) : RuntimeException(message)

class RequestIdConflictException : RuntimeException()

class QuotaExceededException(val retryAfterSeconds: Long?) : RuntimeException()

class QueueFullException : RuntimeException()
