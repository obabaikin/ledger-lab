package com.ledgerlab.ledger.kafka

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff
import tools.jackson.core.JacksonException

@ConfigurationProperties("ledger.topic")
data class TopicProperties(val name: String, val partitions: Int)

@Configuration
class KafkaConfig {

    @Bean
    fun transfersTopic(topic: TopicProperties): NewTopic =
        TopicBuilder.name(topic.name).partitions(topic.partitions).replicas(1).build()

    /**
     * A ledger must never skip a transfer. Transient failures (database down, lock timeout)
     * are retried until they succeed, which stalls the partition rather than losing money.
     * Only messages that can never be parsed are logged and skipped.
     */
    @Bean
    fun kafkaErrorHandler(): DefaultErrorHandler =
        DefaultErrorHandler(FixedBackOff(500L, FixedBackOff.UNLIMITED_ATTEMPTS)).apply {
            addNotRetryableExceptions(JacksonException::class.java)
        }
}
