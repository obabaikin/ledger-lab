package com.ledgerlab.ledger.service;

import com.ledgerlab.ledger.domain.Period;
import com.ledgerlab.ledger.domain.PeriodCheck;
import com.ledgerlab.ledger.persistence.PeriodRepository;
import com.ledgerlab.ledger.persistence.PeriodRepository.AccountMismatches;
import com.ledgerlab.ledger.persistence.ReconciliationProperties;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PeriodChecker {

    private final PeriodRepository periods;
    private final PeriodsProperties props;
    private final ReconciliationProperties reconciliation;

    public PeriodChecker(PeriodRepository periods, PeriodsProperties props, ReconciliationProperties reconciliation) {
        this.periods = periods;
        this.props = props;
        this.reconciliation = reconciliation;
    }

    /**
     * Meant for a period whose transfers are all booked: one booked after this is missing from the snapshot and shows as
     * a mismatch of the next period. REPEATABLE_READ gives every query one snapshot of a bank that keeps booking.
     * Returns false when the check is already there or another instance is making it.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public boolean check(Period period) {
        if (!periods.tryLockCheck(period.start()) || periods.findCheck(period.start()).isPresent()) {
            return false;
        }
        periods.limitStatements(reconciliation.checkTimeout());
        var comparable = periods.hasSnapshot(period.previous().start());
        periods.snapshotBalancesAtEndOf(period);
        var accounts = comparable
            ? periods.accountsOutOfBalance(period, reconciliation.exampleLimit())
            : new AccountMismatches(0, List.of());
        var journal = periods.journalTotals(period);
        var invalid = periods.transfersWithInvalidJournal(period, reconciliation.exampleLimit());
        periods.saveCheck(period.start(), new PeriodCheck(
            journal.entries(),
            journal.debitsCents(),
            journal.creditsCents(),
            invalid.total(),
            invalid.examples(),
            comparable,
            accounts.total(),
            accounts.examples()));
        periods.deleteChecksBefore(period.start().minus(props.snapshotRetention()));
        return true;
    }
}
