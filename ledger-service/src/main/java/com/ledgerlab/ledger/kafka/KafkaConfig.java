package com.ledgerlab.ledger.kafka;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /**
     * A ledger must never skip a transfer. Transient failures (database down, lock timeout)
     * are retried until they succeed, which stalls the partition rather than losing money.
     * A batch is retried whole; messages that can never be parsed are skipped by the consumer, not here.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerProperties consumer) {
        return new DefaultErrorHandler(new FixedBackOff(consumer.retryBackoff().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
    }

    /** Static membership; the listener container suffixes the id per concurrent consumer. */
    @Bean
    public DefaultKafkaConsumerFactoryCustomizer staticMembership(ConsumerProperties consumer) {
        return factory -> {
            var instanceId = consumer.instanceId();
            if (instanceId != null && !instanceId.isBlank()) {
                factory.updateConfigs(Map.of(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, instanceId));
            }
        };
    }
}
