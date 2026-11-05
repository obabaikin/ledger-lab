package com.ledgerlab.ledger.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff
import java.time.Duration

@ConfigurationProperties("ledger.topic")
data class TopicProperties(val name: String)

@ConfigurationProperties("ledger.results")
data class ResultsProperties(
    val topic: String,
    val batchSize: Int,
    val sendTimeout: Duration,
)

@ConfigurationProperties("ledger.consumer")
data class ConsumerProperties(val retryBackoff: Duration, val instanceId: String? = null)

@Configuration
class KafkaConfig {

    /**
     * A ledger must never skip a transfer. Transient failures (database down, lock timeout)
     * are retried until they succeed, which stalls the partition rather than losing money.
     * A batch is retried whole; messages that can never be parsed are skipped by the consumer, not here.
     */
    @Bean
    fun kafkaErrorHandler(consumer: ConsumerProperties): DefaultErrorHandler =
        DefaultErrorHandler(FixedBackOff(consumer.retryBackoff.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS))

    /** Static membership; the listener container suffixes the id per concurrent consumer. */
    @Bean
    fun staticMembership(consumer: ConsumerProperties) = DefaultKafkaConsumerFactoryCustomizer { factory ->
        consumer.instanceId?.takeIf { it.isNotBlank() }?.let { factory.updateConfigs(mapOf(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG to it)) }
    }
}
