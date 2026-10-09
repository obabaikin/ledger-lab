package com.ledgerlab.gateway.kafka

import com.ledgerlab.gateway.GatewayProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff
import tools.jackson.core.JacksonException

@Configuration
class KafkaConfig {

    companion object {
        const val NOTIFIER_CONTAINER_FACTORY = "notifierContainerFactory"
    }

    /**
     * The notifier never commits offsets: MANUAL ack mode with no acknowledgments, and no commit
     * of the initial position on assignment (Spring does that by default).
     */
    @Bean(NOTIFIER_CONTAINER_FACTORY)
    fun notifierContainerFactory(consumerFactory: ConsumerFactory<String, String>) =
        ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(consumerFactory)
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL
            containerProperties.assignmentCommitOption = ContainerProperties.AssignmentCommitOption.NEVER
        }

    /** Same policy as the ledger: a result is never skipped, only unparseable messages are. */
    @Bean
    fun kafkaErrorHandler(props: GatewayProperties): DefaultErrorHandler =
        DefaultErrorHandler(FixedBackOff(props.consumer.retryBackoff.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS)).apply {
            addNotRetryableExceptions(JacksonException::class.java)
        }
}
