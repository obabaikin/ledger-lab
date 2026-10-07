package com.ledgerlab.ledger.kafka

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.ledger.domain.TransferResult
import com.ledgerlab.ledger.service.LedgerService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class TransferConsumer(
    private val ledger: LedgerService,
    private val objectMapper: ObjectMapper,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val posted = outcomeCounter(registry, "posted")
    private val rejected = outcomeCounter(registry, "rejected")
    private val duplicates = outcomeCounter(registry, "duplicate")

    @KafkaListener(topics = ["\${ledger.topic.name}"])
    fun onTransfer(payload: String) {
        val command = objectMapper.readValue(payload, TransferCommand::class.java)
        when (ledger.apply(command)) {
            TransferResult.Posted -> posted.increment()
            is TransferResult.Rejected -> rejected.increment()
            TransferResult.AlreadyProcessed -> {
                duplicates.increment()
                log.debug("Duplicate delivery ignored: run={} transfer={}", command.runId, command.transferId)
            }
        }
    }

    private fun outcomeCounter(registry: MeterRegistry, outcome: String): Counter =
        Counter.builder("ledger.transfers").tag("outcome", outcome).register(registry)
}
