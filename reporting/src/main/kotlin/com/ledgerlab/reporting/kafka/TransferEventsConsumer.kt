package com.ledgerlab.reporting.kafka

import com.ledgerlab.common.TransferCommand
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.reporting.persistence.AcceptedTransfer
import com.ledgerlab.reporting.persistence.TransferOutcomeRow
import com.ledgerlab.reporting.persistence.TransferStatsRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.cfg.EnumFeature

@Component
class TransferEventsConsumer(
    private val repository: TransferStatsRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val commandReader: ObjectReader = objectMapper.readerFor(TransferCommand::class.java)
    private val resultReader: ObjectReader = objectMapper.readerFor(TransferResultEvent::class.java)
        .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)

    @KafkaListener(topics = ["\${reporting.transfers-topic}"], batch = "true")
    @Transactional
    fun onCommands(payloads: List<String>) {
        repository.upsertAccepted(
            payloads.mapNotNull { payload ->
                parsed(payload) { commandReader.readValue<TransferCommand>(payload) }
                    ?.let { AcceptedTransfer(it.transferId, it.runId, it.amountCents, it.createdAt) }
            },
        )
    }

    @KafkaListener(topics = ["\${reporting.results-topic}"], batch = "true")
    @Transactional
    fun onResults(payloads: List<String>) {
        repository.upsertOutcomes(payloads.mapNotNull { payload -> parsed(payload) { readResult(payload) } })
    }

    /** Tolerant reader (ADR 0011): a reject reason this service does not know yet is kept as text. */
    private fun readResult(payload: String): TransferOutcomeRow {
        val tree = objectMapper.readTree(payload)
        val event = resultReader.readValue<TransferResultEvent>(tree)
        val reason = tree.get(TransferResultEvent::rejectReason.name)?.takeUnless { it.isNull }?.asString()
        return TransferOutcomeRow(event.transferId, event.runId, event.outcome.name, reason, event.processedAt)
    }

    private fun <T> parsed(payload: String, read: () -> T): T? =
        try {
            read()
        } catch (e: JacksonException) {
            log.error("Unreadable event skipped: {}", payload, e)
            null
        }
}
