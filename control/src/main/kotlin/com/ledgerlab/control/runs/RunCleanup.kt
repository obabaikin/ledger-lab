package com.ledgerlab.control.runs

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.gateway.GatewayClient
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.ledger.LedgerClient
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class RunCleanup(
    private val runs: RunRepository,
    private val ledger: LedgerClient,
    private val gateway: GatewayClient,
    private val props: ControlProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${control.cleanup.interval}", initialDelayString = "\${control.cleanup.interval}")
    fun purge(): Int {
        var purged = 0
        for (runId in runs.purgeable(props.cleanup.retention)) {
            if (deleted(ledger.delete(runId)) && deleted(gateway.delete(runId))) {
                purged += runs.markPurged(runId)
            } else {
                log.warn("Run {}: bank data not deleted yet, retried in {}", runId, props.cleanup.interval)
            }
        }
        if (purged > 0) log.info("Purged the bank data of {} runs", purged)
        return purged
    }

    private fun deleted(reply: Reply<*>): Boolean =
        reply is Reply.Answered || (reply is Reply.Refused && reply.status == HttpStatus.NOT_FOUND.value())

}
