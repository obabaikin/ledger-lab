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
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

@Component
class TransferConsumer(
    private val ledger: LedgerService,
    objectMapper: ObjectMapper,
    private val duplicates: RunDuplicates,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val reader = objectMapper.readerFor(TransferCommand::class.java)
    private val posted = outcomeCounter(registry, "posted")
    private val rejected = outcomeCounter(registry, "rejected")

    // The id lets the readiness check find this container; without idIsGroup = false it would also replace the group id.
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = ["\${ledger.topic.name}"], batch = "true")
    fun onTransfers(payloads: List<String>) {
        val commands = payloads.mapNotNull(::parse)
        commands.map { it.runId }.distinct().singleOrNull()?.let { MDC.put(MDC_RUN_ID, it.toString()) }
        try {
            commands.zip(ledger.applyBatch(commands)).forEach { (command, result) -> count(command, result) }
        } finally {
            MDC.remove(MDC_RUN_ID)
        }
    }

    // In a batch an exception would fail the whole poll; a message that can never be read is skipped on its own.
    private fun parse(payload: String): TransferCommand? =
        try {
            reader.readValue(payload)
        } catch (e: JacksonException) {
            log.error("Unreadable transfer skipped: {}", payload, e)
            null
        }

    private fun count(command: TransferCommand, result: TransferResult) {
        when (result) {
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
