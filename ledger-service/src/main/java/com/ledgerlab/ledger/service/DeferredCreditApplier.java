package com.ledgerlab.ledger.service;

import com.ledgerlab.ledger.SchedulingConfig;
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

    /**
     * A thread of its own, apart from the result publisher's too: a batch of credits takes the receivers' locks and would
     * hold up the results, as the pool and partition maintenance on the shared thread would hold up the credits.
     */
    @Scheduled(fixedDelayString = "${ledger.deferred-credits.apply-interval}", scheduler = SchedulingConfig.CREDIT_APPLIER_SCHEDULER)
    public void applyPending() {
        int applied;
        do {
            applied = batch.applyNext();
        } while (applied == props.batchSize());
    }
}
