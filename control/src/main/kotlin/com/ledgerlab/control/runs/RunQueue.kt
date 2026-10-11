package com.ledgerlab.control.runs

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.ledger.LedgerReadiness
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class QueueStep(val expired: Int, val admitted: Int)

@Service
class RunQueue(private val runs: RunRepository, private val props: ControlProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun advance(ledgerReady: Boolean): QueueStep {
        runs.lockQueue()
        val expired = runs.expireQueued(props.runs.queueTimeout)
        val free = props.runs.maxActive - runs.countIn(RunState.entries.filter { it.holdsSlot })
        val admitted = if (ledgerReady && free > 0) runs.admitOldest(free) else 0
        if (expired > 0 || admitted > 0) log.info("Run queue: {} expired, {} admitted", expired, admitted)
        return QueueStep(expired, admitted)
    }
}

@Component
class QueueScheduler(private val ledger: LedgerReadiness, private val queue: RunQueue) {

    // The readiness call stays outside the queue's transaction, so a slow ledger never holds the lock.
    @Scheduled(fixedDelayString = "\${control.queue.poll-interval}", initialDelayString = "\${control.queue.poll-interval}")
    fun tick(): QueueStep = queue.advance(ledger.isReady())
}
