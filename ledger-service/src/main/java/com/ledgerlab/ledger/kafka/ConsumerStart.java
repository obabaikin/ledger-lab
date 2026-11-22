package com.ledgerlab.ledger.kafka;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Starts the transfer consumer and returns once it holds partitions. Spring Boot reports the instance ready only after
 * its runners, so a deploy replaces the next instance only when this one reads; later rebalances do not touch readiness
 * (ADR 0012).
 */
@Component
public class ConsumerStart implements ApplicationRunner {

    private static final Duration ASSIGNMENT_POLL = Duration.ofMillis(100);

    private static final Logger log = LoggerFactory.getLogger(ConsumerStart.class);

    private final KafkaListenerEndpointRegistry listeners;
    private final ConsumerProperties props;

    public ConsumerStart(KafkaListenerEndpointRegistry listeners, ConsumerProperties props) {
        this.listeners = listeners;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        var container = Objects.requireNonNull(listeners.getListenerContainer(TransferConsumer.LISTENER_ID), "transfer consumer");
        container.start();
        awaitAssignment();
    }

    private void awaitAssignment() throws InterruptedException {
        var started = System.nanoTime();
        var deadline = started + props.assignmentWait().toNanos();
        while (AssignedPartitions.count(listeners) == 0) {
            if (System.nanoTime() >= deadline) {
                log.warn("No partition assigned within {}; the instance reports ready anyway", props.assignmentWait());
                return;
            }
            Thread.sleep(ASSIGNMENT_POLL);
        }
        log.info("Reading {} partitions {} ms after the consumer started",
            AssignedPartitions.count(listeners), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
