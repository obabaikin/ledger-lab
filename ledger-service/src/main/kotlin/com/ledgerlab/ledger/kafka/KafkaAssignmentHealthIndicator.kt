package com.ledgerlab.ledger.kafka

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
        val assigned = listeners.getListenerContainer(TransferConsumer.LISTENER_ID)?.assignedPartitions.orEmpty()
        return if (assigned.isEmpty()) Health.down().build() else Health.up().withDetail(ASSIGNED_PARTITIONS, assigned.size).build()
    }

    private companion object {
        const val ASSIGNED_PARTITIONS = "assignedPartitions"
    }
}
