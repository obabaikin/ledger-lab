package com.ledgerlab.ledger.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeferredCreditApplier {

    private final DeferredCreditBatch batch;
    private final DeferredCreditProperties props;

    public DeferredCreditApplier(DeferredCreditBatch batch, DeferredCreditProperties props) {
        this.batch = batch;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${ledger.deferred-credits.apply-interval}")
    public void applyPending() {
        int applied;
        do {
            applied = batch.applyNext();
        } while (applied == props.batchSize());
    }
}
