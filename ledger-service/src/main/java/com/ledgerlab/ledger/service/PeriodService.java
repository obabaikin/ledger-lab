package com.ledgerlab.ledger.service;

import com.ledgerlab.ledger.domain.Books;
import com.ledgerlab.ledger.domain.Period;
import com.ledgerlab.ledger.domain.PeriodReconciliation;
import com.ledgerlab.ledger.persistence.AccountRepository;
import com.ledgerlab.ledger.persistence.PeriodRepository;
import com.ledgerlab.ledger.persistence.ReconciliationProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final AccountRepository accounts;
    private final PeriodsProperties props;
    private final ReconciliationProperties reconciliation;
    private final Clock clock;

    public PeriodService(
        PeriodRepository periods,
        AccountRepository accounts,
        PeriodsProperties props,
        ReconciliationProperties reconciliation,
        Clock clock
    ) {
        this.periods = periods;
        this.accounts = accounts;
        this.props = props;
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    /**
     * A period the ledger can account for: on the grid, ended, and not older than the retention, past which its
     * partition is dropped.
     */
    public Period endedPeriod(Instant periodStart) {
        var now = clock.instant();
        var period = new Period(periodStart, periodStart.plus(props.length()));
        if (!Period.startsOnGrid(periodStart, props.length()) || period.end().isAfter(now) || !period.end().isAfter(now.minus(props.retention()))) {
            throw new UnknownPeriodException(periodStart);
        }
        return period;
    }

    @Transactional(readOnly = true)
    public PeriodReconciliation reconciliation(Instant periodStart) {
        var period = endedPeriod(periodStart);
        periods.limitStatements(reconciliation.statementTimeout());
        var counts = periods.countTransfers(period);
        return new PeriodReconciliation(
            period.start(),
            counts.posted(),
            counts.rejected(),
            counts.rejectionsByReason(),
            counts.poolPostings(),
            periods.findCheck(period.start()).orElse(null));
    }

    @Transactional(readOnly = true)
    public Books books() {
        periods.limitStatements(reconciliation.statementTimeout());
        return accounts.books();
    }
}
