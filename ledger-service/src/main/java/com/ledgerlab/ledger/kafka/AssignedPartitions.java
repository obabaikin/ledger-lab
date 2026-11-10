package com.ledgerlab.ledger.kafka;

import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

final class AssignedPartitions {

    private AssignedPartitions() {
    }

    static int count(KafkaListenerEndpointRegistry listeners) {
        var container = listeners.getListenerContainer(TransferConsumer.LISTENER_ID);
        if (container == null) {
            return 0;
        }
        var assigned = container.getAssignedPartitions();
        return assigned == null ? 0 : assigned.size();
    }
}
