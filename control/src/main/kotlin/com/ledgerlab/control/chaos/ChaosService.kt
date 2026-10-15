package com.ledgerlab.control.chaos

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.entry.InviteRepository
import com.ledgerlab.control.entry.SessionNotFoundException
import com.ledgerlab.control.runs.RunRepository
import com.ledgerlab.control.runs.RunState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ChaosNotAvailableException : RuntimeException()

class NoRunningRunException : RuntimeException()

class ChaosUsedRecentlyException(val retryAfterSeconds: Long) : RuntimeException()

class LedgerDidNotAnswerException : RuntimeException()

@Service
class ChaosService(
    private val invites: InviteRepository,
    private val runs: RunRepository,
    private val events: ChaosRepository,
    private val ledger: LedgerInstances,
    private val props: ControlProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The lock is held over the calls to the instances, so a second press waits and sees this one's event.
     * The time is taken after the lock, as in [RunRepository]: the waiting transaction's now() would lie before it.
     */
    @Transactional
    fun crash(sessionId: UUID): ChaosEvent {
        invites.findSession(sessionId) ?: throw SessionNotFoundException()
        if (!props.chaos.enabled) throw ChaosNotAvailableException()
        if (!runs.hasRun(sessionId, listOf(RunState.RUNNING))) throw NoRunningRunException()
        events.lock()
        val at = runs.now()
        val readyIn = secondsUntilReady(events.lastAt(), at)
        if (readyIn > 0) throw ChaosUsedRecentlyException(readyIn)
        val crashed = ledger.crashAll()
        if (crashed.isEmpty()) throw LedgerDidNotAnswerException()
        return ChaosEvent(at, crashed).also {
            events.insert(it)
            log.info("Chaos: crashed {}", crashed)
        }
    }

    /** 0 when chaos may be used now; the same number a refused press sends as Retry-After. */
    @Transactional(readOnly = true)
    fun readyInSeconds(): Long = if (props.chaos.enabled) secondsUntilReady(events.lastAt(), runs.now()) else 0

    private fun secondsUntilReady(lastAt: Instant?, now: Instant): Long {
        val left = lastAt?.let { Duration.between(now, it + props.chaos.minInterval) } ?: return 0
        if (left <= Duration.ZERO) return 0
        return Math.ceilDiv(left.toMillis(), MILLIS_PER_SECOND).coerceAtLeast(1)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
