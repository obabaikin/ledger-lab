package com.ledgerlab.gateway.kafka

import com.ledgerlab.gateway.GatewayProperties
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory
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

        /**
         * Spring stops the higher phase first, and the listeners' default phase is above the web server's drain: they
         * would stop before it, and a request still waiting while the pod stops would never be woken. Every factory
         * gets the same phase, as the registry refuses factories that differ.
         */
        @Bean
        @JvmStatic
        fun listenersStopAfterRequests() = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                bean.also { if (it is AbstractKafkaListenerContainerFactory<*, *, *>) it.setPhase(LISTENER_PHASE) }
        }

        private const val LISTENER_PHASE = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE - 1
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

    /** A result is never skipped; an unparseable message is (the recorder skips it itself, its poll being a batch). */
    @Bean
    fun kafkaErrorHandler(props: GatewayProperties): DefaultErrorHandler =
        DefaultErrorHandler(FixedBackOff(props.consumer.retryBackoff.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS)).apply {
            addNotRetryableExceptions(JacksonException::class.java)
        }
}
