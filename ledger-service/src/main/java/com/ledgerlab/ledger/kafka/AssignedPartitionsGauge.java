package com.ledgerlab.ledger.kafka;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/** Alive but holding no partition books nothing: the page shows it per instance, next to `up`. */
@Component
public class AssignedPartitionsGauge {

    public static final String METRIC = "ledger.kafka.assigned.partitions";

    public AssignedPartitionsGauge(KafkaListenerEndpointRegistry listeners, MeterRegistry registry) {
        Gauge.builder(METRIC, listeners, AssignedPartitions::count).register(registry);
    }
}
