package com.ledgerlab.gateway.kafka

import com.ledgerlab.gateway.GatewayProperties
import com.ledgerlab.gateway.persistence.TransferRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Component
class OutboxPublisher(private val batch: OutboxBatchPublisher, private val props: GatewayProperties) {

    @Scheduled(fixedDelayString = "\${gateway.outbox.publish-interval}")
    fun publishPending() {
        var published: Int
        do {
            published = batch.publishOne()
        } while (published == props.outbox.batchSize)
    }
}

/**
 * One batch per transaction: claim with SKIP LOCKED, send, mark published. Several gateway
 * replicas can run this concurrently without publishing the same row twice; a crash between
 * send and mark re-sends the batch, which the ledger's idempotency absorbs.
 */
@Component
class OutboxBatchPublisher(
    private val transfers: TransferRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val props: GatewayProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun publishOne(): Int {
        val commands = transfers.claimUnpublished(props.outbox.batchSize)
        if (commands.isEmpty()) return 0
        val sends = commands.map { command ->
            kafka.send(props.transfersTopic, command.partitionKey(), objectMapper.writeValueAsString(command))
        }
        CompletableFuture.allOf(*sends.toTypedArray()).get(props.outbox.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
        transfers.markPublished(commands.map { it.transferId })
        log.debug("Published {} transfer commands", commands.size)
        return commands.size
    }
}
