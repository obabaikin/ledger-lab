package com.ledgerlab.gateway.kafka;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.SchedulingConfig;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {

    private final OutboxBatchPublisher batch;
    private final GatewayProperties props;

    public OutboxPublisher(OutboxBatchPublisher batch, GatewayProperties props) {
        this.batch = batch;
        this.props = props;
    }

    /**
     * A thread of its own: on the shared one it waited behind key cleanup and partition maintenance, and no command
     * reached Kafka meanwhile. One thread, as two publishers in one instance would reorder a sender's transfers.
     */
    @Scheduled(fixedDelayString = "${gateway.outbox.publish-interval}", scheduler = SchedulingConfig.OUTBOX_PUBLISHER_SCHEDULER)
    public void publishPending() throws InterruptedException, ExecutionException, TimeoutException {
        int published;
        do {
            published = batch.publishOne();
        } while (published == props.outbox().batchSize());
    }
}
