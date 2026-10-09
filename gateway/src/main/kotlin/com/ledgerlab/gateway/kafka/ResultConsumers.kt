package com.ledgerlab.gateway.kafka

import com.ledgerlab.common.Observability.MDC_RUN_ID
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.gateway.service.ResultWaiters
import com.ledgerlab.gateway.service.TransferService
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Durable path: exactly one gateway instance records each result in the database. */
@Component
class ResultRecorder(private val service: TransferService, private val objectMapper: ObjectMapper) {

    @KafkaListener(topics = ["\${gateway.results-topic}"], groupId = "\${spring.kafka.consumer.group-id}")
    fun onResult(payload: String) {
        val event = objectMapper.readValue(payload, TransferResultEvent::class.java)
        MDC.put(MDC_RUN_ID, event.runId.toString())
        try {
            service.recordResult(event)
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
class ResultNotifier(private val waiters: ResultWaiters, private val objectMapper: ObjectMapper) {

    @KafkaListener(
        topics = ["\${gateway.results-topic}"],
        groupId = "gateway-notify-#{T(java.util.UUID).randomUUID()}",
        containerFactory = KafkaConfig.NOTIFIER_CONTAINER_FACTORY,
        properties = ["auto.offset.reset=latest"],
    )
    fun onResult(payload: String) {
        waiters.complete(objectMapper.readValue(payload, TransferResultEvent::class.java))
    }
}
