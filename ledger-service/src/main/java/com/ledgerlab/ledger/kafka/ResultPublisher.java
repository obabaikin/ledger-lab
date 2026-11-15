package com.ledgerlab.ledger.kafka;

import com.ledgerlab.ledger.SchedulingConfig;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResultPublisher {

    private final ResultBatchPublisher batch;
    private final ResultsProperties results;

    public ResultPublisher(ResultBatchPublisher batch, ResultsProperties results) {
        this.batch = batch;
        this.results = results;
    }

    /**
     * A thread of its own: on the shared one it waited behind the credits, the pool and partition maintenance, and no
     * result reached Kafka meanwhile.
     */
    @Scheduled(fixedDelayString = "${ledger.results.publish-interval}", scheduler = SchedulingConfig.RESULT_PUBLISHER_SCHEDULER)
    public void publishPending() throws InterruptedException, ExecutionException, TimeoutException {
        int published;
        do {
            published = batch.publishOne();
        } while (published == results.batchSize());
    }
}
