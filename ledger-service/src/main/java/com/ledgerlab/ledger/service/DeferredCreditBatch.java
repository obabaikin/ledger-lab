package com.ledgerlab.ledger.service;

import com.ledgerlab.ledger.domain.AccountKey;
import com.ledgerlab.ledger.persistence.AccountRepository;
import com.ledgerlab.ledger.persistence.DeferredCreditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeferredCreditBatch {

    private final DeferredCreditRepository credits;
    private final AccountRepository accounts;
    private final DeferredCreditProperties props;
    private final Timer delay;

    public DeferredCreditBatch(
        DeferredCreditRepository credits,
        AccountRepository accounts,
        DeferredCreditProperties props,
        MeterRegistry registry
    ) {
        this.credits = credits;
        this.accounts = accounts;
        this.props = props;
        this.delay = Timer.builder("ledger.credit.delay")
            .description("From posting a transfer to its credit reaching the receiver's balance")
            .publishPercentileHistogram()
            .register(registry);
    }

    /** Returns how many credits it applied; 0 also when another applier holds the lock. */
    @Transactional
    public int applyNext() {
        if (!credits.tryLockApplier()) {
            return 0;
        }
        var applied = credits.takePending(props.batchSize());
        if (applied.isEmpty()) {
            return 0;
        }
        var amounts = new LinkedHashMap<AccountKey, Long>();
        applied.forEach(credit -> amounts.merge(credit.account(), credit.amountCents(), Long::sum));
        accounts.addToBalances(amounts);
        applied.forEach(credit -> delay.record(credit.waited()));
        return applied.size();
    }
}
