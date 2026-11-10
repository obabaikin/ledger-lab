package com.ledgerlab.ledger.kafka;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/** Not part of the readiness probe: a rebalance would take every instance out of the Service at once (ADR 0012). */
@Component("kafkaAssignment")
public class KafkaAssignmentHealthIndicator implements HealthIndicator {

    private static final String ASSIGNED_PARTITIONS = "assignedPartitions";

    private final KafkaListenerEndpointRegistry listeners;

    public KafkaAssignmentHealthIndicator(KafkaListenerEndpointRegistry listeners) {
        this.listeners = listeners;
    }

    @Override
    public Health health() {
        var assigned = AssignedPartitions.count(listeners);
        return assigned == 0 ? Health.down().build() : Health.up().withDetail(ASSIGNED_PARTITIONS, assigned).build();
    }
}
