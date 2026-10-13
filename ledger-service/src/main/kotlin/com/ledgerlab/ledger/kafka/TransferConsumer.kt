package com.ledgerlab.ledger.kafka

import com.ledgerlab.common.Observability.MDC_RUN_ID
import com.ledgerlab.common.TransferCommand
import com.ledgerlab.ledger.domain.TransferResult
import com.ledgerlab.ledger.metrics.RunDuplicates
import com.ledgerlab.ledger.service.LedgerService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class TransferConsumer(
    private val ledger: LedgerService,
    private val objectMapper: ObjectMapper,
    private val duplicates: RunDuplicates,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val posted = outcomeCounter(registry, "posted")
    private val rejected = outcomeCounter(registry, "rejected")

    // The id lets the readiness check find this container; without idIsGroup = false it would also replace the group id.
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = ["\${ledger.topic.name}"])
    fun onTransfer(payload: String) {
        val command = objectMapper.readValue(payload, TransferCommand::class.java)
        MDC.put(MDC_RUN_ID, command.runId.toString())
        try {
            handle(command)
        } finally {
            MDC.remove(MDC_RUN_ID)
        }
    }

    private fun handle(command: TransferCommand) {
        when (ledger.apply(command)) {
            TransferResult.Posted -> posted.increment()
            is TransferResult.Rejected -> rejected.increment()
            TransferResult.AlreadyProcessed -> {
                duplicates.record(command.runId)
                log.debug("Duplicate delivery ignored: run={} transfer={}", command.runId, command.transferId)
            }
        }
    }

    private fun outcomeCounter(registry: MeterRegistry, outcome: String): Counter =
        Counter.builder("ledger.transfers").tag("outcome", outcome).register(registry)

    companion object {
        const val LISTENER_ID = "transfers"
    }
}
