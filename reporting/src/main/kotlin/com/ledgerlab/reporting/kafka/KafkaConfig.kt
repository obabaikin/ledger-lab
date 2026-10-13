package com.ledgerlab.reporting.kafka

import com.ledgerlab.reporting.ReportingProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.backoff.FixedBackOff
import org.springframework.kafka.listener.DefaultErrorHandler

@Configuration
class KafkaConfig {

    /** A batch is retried until it is stored: an event skipped here would make the projection disagree with the books forever. */
    @Bean
    fun kafkaErrorHandler(props: ReportingProperties): DefaultErrorHandler =
        DefaultErrorHandler(FixedBackOff(props.consumer.retryBackoff.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS))
}
