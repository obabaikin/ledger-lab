package com.ledgerlab.gateway.kafka;

import com.ledgerlab.gateway.GatewayProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class KafkaConfig {

    public static final String NOTIFIER_CONTAINER_FACTORY = "notifierContainerFactory";

    private static final int LISTENER_PHASE = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE - 1;

    /**
     * Spring stops the higher phase first, and the listeners' default phase is above the web server's drain: they
     * would stop before it, and a request still waiting while the pod stops would never be woken. Every factory
     * gets the same phase, as the registry refuses factories that differ.
     */
    @Bean
    public static BeanPostProcessor listenersStopAfterRequests() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                    factory.setPhase(LISTENER_PHASE);
                }
                return bean;
            }
        };
    }

    /**
     * The notifier never commits offsets: MANUAL ack mode with no acknowledgments, and no commit
     * of the initial position on assignment (Spring does that by default).
     */
    @Bean(NOTIFIER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> notifierContainerFactory(ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
        return factory;
    }

    /** A result is never skipped; an unparseable message is (the recorder skips it itself, its poll being a batch). */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(GatewayProperties props) {
        var handler = new DefaultErrorHandler(new FixedBackOff(props.consumer().retryBackoff().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
