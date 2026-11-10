package com.ledgerlab.ledger.kafka;

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

    @Scheduled(fixedDelayString = "${ledger.results.publish-interval}")
    public void publishPending() throws InterruptedException, ExecutionException, TimeoutException {
        int published;
        do {
            published = batch.publishOne();
        } while (published == results.batchSize());
    }
}
