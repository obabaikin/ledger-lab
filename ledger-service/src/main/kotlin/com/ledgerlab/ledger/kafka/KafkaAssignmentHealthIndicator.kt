package com.ledgerlab.ledger.kafka

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.stereotype.Component

/**
 * Ready only while the transfer consumer holds at least one partition. After a restart the
 * service answers HTTP before the group assigns partitions, and a run started in that window waits for them.
 */
@Component("kafkaAssignment")
class KafkaAssignmentHealthIndicator(private val listeners: KafkaListenerEndpointRegistry) : HealthIndicator {

    override fun health(): Health {
        val assigned = listeners.assignedPartitions()
        return if (assigned == 0) Health.down().build() else Health.up().withDetail(ASSIGNED_PARTITIONS, assigned).build()
    }

    private companion object {
        const val ASSIGNED_PARTITIONS = "assignedPartitions"
    }
}

/** Alive but holding no partition books nothing: the page shows it per instance, next to `up`. */
@Component
class AssignedPartitionsGauge(listeners: KafkaListenerEndpointRegistry, registry: MeterRegistry) {
    init {
        Gauge.builder(METRIC, listeners) { it.assignedPartitions().toDouble() }.register(registry)
    }

    companion object {
        const val METRIC = "ledger.kafka.assigned.partitions"
    }
}

private fun KafkaListenerEndpointRegistry.assignedPartitions(): Int =
    getListenerContainer(TransferConsumer.LISTENER_ID)?.assignedPartitions.orEmpty().size
