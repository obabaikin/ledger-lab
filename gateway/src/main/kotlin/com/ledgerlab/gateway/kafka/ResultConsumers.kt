package com.ledgerlab.gateway.kafka

import com.ledgerlab.common.Observability.MDC_RUN_ID
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.gateway.service.LedgerResult
import com.ledgerlab.gateway.service.ResultWaiters
import com.ledgerlab.gateway.service.TransferService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.cfg.EnumFeature

/**
 * Tolerant reader of the ledger's result events (ADR 0011): a ledger one version ahead may send a reject
 * reason this gateway does not know yet. The outcome still applies, so the transfer is not left PUBLISHED;
 * the reason is kept as text, logged and counted.
 */
@Component
class ResultEventReader(private val objectMapper: ObjectMapper, private val registry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val reader: ObjectReader = objectMapper.readerFor(TransferResultEvent::class.java)
        .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)

    fun read(payload: String, listener: Listener): LedgerResult {
        val tree = objectMapper.readTree(payload)
        val event = reader.readValue<TransferResultEvent>(tree)
        val reason = tree.textOf(TransferResultEvent::rejectReason.name)
        if (reason != null && event.rejectReason == null) {
            registry.counter(UNKNOWN_REASON_METRIC, LISTENER_TAG, listener.tag).increment()
            log.warn("Result of transfer {} has a reject reason this gateway does not know: {}", event.transferId, reason)
        }
        return LedgerResult(event.runId, event.transferId, event.outcome, reason)
    }

    private fun JsonNode.textOf(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

    enum class Listener(val tag: String) { RECORDER("recorder"), NOTIFIER("notifier") }

    companion object {
        const val UNKNOWN_REASON_METRIC = "gateway.results.unknown-reason"
        const val LISTENER_TAG = "listener"
    }
}

/** Durable path: exactly one gateway instance records each result in the database. */
@Component
class ResultRecorder(private val service: TransferService, private val reader: ResultEventReader) {

    @KafkaListener(topics = ["\${gateway.results-topic}"], groupId = "\${spring.kafka.consumer.group-id}")
    fun onResult(payload: String) {
        val result = reader.read(payload, ResultEventReader.Listener.RECORDER)
        MDC.put(MDC_RUN_ID, result.runId.toString())
        try {
            service.recordResult(result)
        } finally {
            MDC.remove(MDC_RUN_ID)
        }
    }
}

/**
 * Notification path: every instance sees every result so that the instance holding an open
 * request can answer it. Per-instance anonymous group that never commits offsets: nothing to
 * replay after a restart, and the broker drops the empty group instead of keeping it.
 */
@Component
class ResultNotifier(private val waiters: ResultWaiters, private val reader: ResultEventReader) {

    @KafkaListener(
        topics = ["\${gateway.results-topic}"],
        groupId = "gateway-notify-#{T(java.util.UUID).randomUUID()}",
        containerFactory = KafkaConfig.NOTIFIER_CONTAINER_FACTORY,
        properties = ["auto.offset.reset=latest"],
    )
    fun onResult(payload: String) {
        waiters.complete(reader.read(payload, ResultEventReader.Listener.NOTIFIER))
    }
}
