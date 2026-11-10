package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.domain.ClosedPeriod;
import com.ledgerlab.gateway.domain.Period;
import com.ledgerlab.gateway.domain.PeriodSummary;
import com.ledgerlab.gateway.domain.Statement;
import com.ledgerlab.gateway.domain.StatementCursor;
import com.ledgerlab.gateway.persistence.PeriodRepository;
import com.ledgerlab.gateway.persistence.TransferRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final TransferRepository transfers;
    private final GatewayProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PeriodService(PeriodRepository periods, TransferRepository transfers, GatewayProperties props, Clock clock, TransactionTemplate tx) {
        this.periods = periods;
        this.transfers = transfers;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * A transfer belongs to the period of its accepted_at, stamped while its insert holds the accept lock. The period
     * has ended by the database's clock before the close takes that lock, so every insert stamped in the period held
     * the lock earlier and is committed or gone by now, and every later one is stamped after the period's end. The
     * lock is given back at once and the count runs without it: accepting waits only for inserts in flight.
     */
    public ClosedPeriod close(Instant periodStart) {
        var period = heldPeriod(periodStart);
        var closed = periods.find(periodStart);
        if (closed != null) {
            return closed;
        }
        tx.executeWithoutResult(status -> {
            if (!periods.hasEnded(period)) {
                throw new UnknownPeriodException(periodStart, "it has not ended");
            }
            periods.awaitAcceptsInFlight(props.closeLockTimeout());
        });
        var inserted = periods.insertIfAbsent(period);
        return inserted != null ? inserted : Objects.requireNonNull(periods.find(periodStart), "the period someone else closed meanwhile");
    }

    public PeriodSummary summarize(Instant periodStart) {
        return transfers.summarize(heldPeriod(periodStart));
    }

    /** Only a closed period has a statement: before that, rows may still commit behind the cursor. */
    public Statement statement(Instant periodStart, @Nullable StatementCursor after, int limit) {
        var period = heldPeriod(periodStart);
        var closed = periods.find(periodStart);
        if (closed == null) {
            throw new PeriodNotClosedException(periodStart);
        }
        if (after != null && !period.contains(after.acceptedAt())) {
            throw new IllegalArgumentException("The cursor is not of the period from " + periodStart);
        }
        var rows = transfers.statementPage(period, after, limit + 1);
        var items = rows.size() > limit ? rows.subList(0, limit) : rows;
        return new Statement(
            periodStart, closed.closedAt(), closed.entryCount(), items, rows.size() > limit ? StatementCursor.after(items.getLast()).text() : null);
    }

    /** A period that has begun and whose transfers are still held. */
    private Period heldPeriod(Instant periodStart) {
        var length = props.periods().length();
        if (!Period.startsOnGrid(periodStart, length)) {
            throw new UnknownPeriodException(periodStart, "periods are " + length + " long from the epoch");
        }
        var now = clock.instant();
        var period = new Period(periodStart, periodStart.plus(length));
        if (periodStart.isAfter(now)) {
            throw new UnknownPeriodException(periodStart, "it has not begun");
        }
        if (!period.end().isAfter(now.minus(props.periods().retention()))) {
            throw new UnknownPeriodException(periodStart, "it is older than the retention of " + props.periods().retention());
        }
        return period;
    }
}
