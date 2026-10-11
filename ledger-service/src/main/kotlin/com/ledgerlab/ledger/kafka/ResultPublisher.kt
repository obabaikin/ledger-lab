package com.ledgerlab.ledger.kafka

import com.ledgerlab.ledger.persistence.ResultOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Component
class ResultPublisher(private val batch: ResultBatchPublisher, private val results: ResultsProperties) {

    @Scheduled(fixedDelayString = "\${ledger.results.publish-interval}")
    fun publishPending() {
        var published: Int
        do {
            published = batch.publishOne()
        } while (published == results.batchSize)
    }
}

/**
 * One batch per transaction: claim rows with SKIP LOCKED, send, mark published.
 * A crash between send and mark re-sends the batch (at-least-once); consumers are idempotent.
 */
@Component
class ResultBatchPublisher(
    private val outbox: ResultOutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val results: ResultsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun publishOne(): Int {
        val events = outbox.claimUnpublished(results.batchSize)
        if (events.isEmpty()) return 0
        val sends = events.map { event ->
            kafka.send(results.topic, event.partitionKey(), objectMapper.writeValueAsString(event))
        }
        CompletableFuture.allOf(*sends.toTypedArray()).get(results.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
        outbox.markPublished(events)
        log.debug("Published {} transfer results", events.size)
        return events.size
    }
}
