package com.ledgerlab.generator;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Client reconciliations run in the background, one at a time: reading a period's statement takes longer than a
 * caller waits. Their state is kept in memory for as long as the journal holds the period.
 */
@Component
public class PeriodReconciliations implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PeriodReconciliations.class);

    private final ClientReconciliation reconciliation;
    private final GeneratorProperties.Periods periods;
    private final Clock clock;
    private final ConcurrentHashMap<Instant, PeriodReconciliation> byPeriod = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("client-reconciliation").factory());

    public PeriodReconciliations(ClientReconciliation reconciliation, GeneratorProperties props, Clock clock) {
        this.reconciliation = reconciliation;
        this.periods = props.periods();
        this.clock = clock;
    }

    /** Starts the reconciliation, also one that was done or failed before; one that is running is not started twice. */
    public PeriodReconciliation start(Instant periodStart) {
        var period = ended(periodStart);
        return byPeriod.compute(periodStart, (start, known) -> {
            if (known != null && known.state() == ReconciliationState.RUNNING) {
                return known;
            }
            runner.execute(() -> byPeriod.put(start, run(period)));
            return PeriodReconciliation.of(start, ReconciliationState.RUNNING);
        });
    }

    public PeriodReconciliation get(Instant periodStart) {
        ended(periodStart);
        byPeriod.keySet().removeIf(start -> isExpired(start, clock.instant()));
        return byPeriod.getOrDefault(periodStart, PeriodReconciliation.of(periodStart, ReconciliationState.NOT_STARTED));
    }

    private PeriodReconciliation run(Period period) {
        try {
            var report = reconciliation.reconcile(period);
            log.info("Client reconciliation of the period from {}: balanced={} lines={}", period.start(), report.balanced(), report.statement().rows());
            return new PeriodReconciliation(period.start(), ReconciliationState.DONE, null, report);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(period, ReconciliationFailure.INTERNAL_ERROR, e);
        } catch (PeriodNotClosedException e) {
            return failed(period, ReconciliationFailure.PERIOD_NOT_CLOSED, e);
        } catch (GatewayUnavailableException e) {
            return failed(period, ReconciliationFailure.GATEWAY_UNAVAILABLE, e);
        } catch (BadGatewayAnswerException e) {
            return failed(period, ReconciliationFailure.BAD_GATEWAY_ANSWER, e);
        } catch (DataAccessException | TransactionException e) {
            return failed(period, ReconciliationFailure.JOURNAL_UNAVAILABLE, e);
        } catch (RuntimeException e) {
            return failed(period, ReconciliationFailure.INTERNAL_ERROR, e);
        }
    }

    private static PeriodReconciliation failed(Period period, ReconciliationFailure failure, Exception cause) {
        log.warn("Client reconciliation of the period from {} failed: {}", period.start(), failure, cause);
        return new PeriodReconciliation(period.start(), ReconciliationState.FAILED, failure, null);
    }

    /** The period, if it lies on the grid, has ended and the journal still holds it. */
    private Period ended(Instant periodStart) {
        var now = clock.instant();
        if (!Period.startsOnGrid(periodStart, periods.length())) {
            throw new UnknownPeriodException("No period starts at " + periodStart);
        }
        var period = Period.containing(periodStart, periods.length());
        if (period.end().isAfter(now)) {
            throw new UnknownPeriodException("The period from " + periodStart + " has not ended");
        }
        if (isExpired(periodStart, now)) {
            throw new UnknownPeriodException("The period from " + periodStart + " is older than the journal holds");
        }
        return period;
    }

    /**
     * A period's statement may list a transfer sent in the period before, so a period counts as held only while the
     * journal of the one before is: that one's partitions are dropped a period earlier than its own.
     */
    private boolean isExpired(Instant periodStart, Instant now) {
        return !periodStart.isAfter(now.minus(periods.retention()));
    }

    @Override
    public void destroy() {
        runner.shutdownNow();
    }
}
