package com.ledgerlab.gateway.kafka;

import com.ledgerlab.gateway.GatewayProperties;
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

    @Scheduled(fixedDelayString = "${gateway.outbox.publish-interval}")
    public void publishPending() throws InterruptedException, ExecutionException, TimeoutException {
        int published;
        do {
            published = batch.publishOne();
        } while (published == props.outbox().batchSize());
    }
}
