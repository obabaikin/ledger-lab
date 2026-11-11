package com.ledgerlab.control.periods;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.DatabaseClock;
import com.ledgerlab.control.bank.BankLive;
import com.ledgerlab.control.chaos.ChaosRepository;
import com.ledgerlab.control.gateway.ClosedPeriod;
import com.ledgerlab.control.gateway.GatewayClient;
import com.ledgerlab.control.gateway.PeriodSummary;
import com.ledgerlab.control.generator.ClientReconciliation;
import com.ledgerlab.control.generator.ClientReconciliationReport;
import com.ledgerlab.control.generator.ClientReconciliationState;
import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.ledger.Books;
import com.ledgerlab.control.ledger.LedgerClient;
import com.ledgerlab.control.ledger.LedgerReconciliation;
import com.ledgerlab.control.reporting.PeriodStats;
import com.ledgerlab.control.reporting.ReportingClient;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Reconciles the bank's periods one at a time, oldest first: the ledger compares a period's balances with those of the
 * period before, and the clients settle what they did not know of the period before.
 */
@Service
public class PeriodConductor {

    private static final Logger log = LoggerFactory.getLogger(PeriodConductor.class);

    private final PeriodRepository periods;
    private final LedgerClient ledger;
    private final GatewayClient gateway;
    private final GeneratorClient generator;
    private final ReportingClient reporting;
    private final BankLive live;
    private final ChaosRepository chaos;
    private final DatabaseClock clock;
    private final ControlProperties.Periods props;
    private final ControlProperties.Reporting reportingProps;

    public PeriodConductor(
        PeriodRepository periods,
        LedgerClient ledger,
        GatewayClient gateway,
        GeneratorClient generator,
        ReportingClient reporting,
        BankLive live,
        ChaosRepository chaos,
        DatabaseClock clock,
        ControlProperties props
    ) {
        this.periods = periods;
        this.ledger = ledger;
        this.gateway = gateway;
        this.generator = generator;
        this.reporting = reporting;
        this.live = live;
        this.chaos = chaos;
        this.clock = clock;
        this.props = props.periods();
        this.reportingProps = props.reporting();
    }

    public void tick() {
        tick(clock.now());
    }

    /** The live numbers are read beside the period's step, so a slow call of one does not hold up the other. */
    public void tick(Instant now) {
        try (var work = Executors.newVirtualThreadPerTaskExecutor()) {
            work.execute(() -> step(now));
            work.execute(() -> refreshLive(now));
        }
    }

    private void refreshLive(Instant now) {
        try {
            live.refresh(now, periods.unfinishedStarts());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Live numbers not read, retried on the next tick", e);
        }
    }

    private void step(Instant now) {
        BankPeriod period = null;
        try {
            periods.openUpTo(Period.containing(now, props.length()), props.length());
            period = periods.oldestUnfinished();
            if (period == null) {
                return;
            }
            switch (period.state()) {
                case OPEN -> open(period, now);
                case CLOSING -> close(period, now);
                case DRAINING -> drain(period, now);
                default -> {
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Period {}: step failed, retried on the next tick", period == null ? "unknown" : period.start(), e);
        }
    }

    private void open(BankPeriod period, Instant now) throws InterruptedException {
        if (now.isBefore(period.end().plus(props.closeDelay()))) {
            return;
        }
        if (!now.isBefore(period.start().plus(props.detailsRetention()))) {
            failed(period, PeriodFailure.DETAILS_EXPIRED, null, now, "the bank keeps a period's transfers for " + props.detailsRetention());
            return;
        }
        if (periods.markClosing(period.start(), now) == 1) {
            close(Objects.requireNonNull(periods.find(period.start()), "the period just marked"), now);
        }
    }

    private void close(BankPeriod period, Instant now) throws InterruptedException {
        var reply = gateway.close(period.start());
        if (reply instanceof Reply.Answered<ClosedPeriod>(var code, var answered)) {
            var closed = Objects.requireNonNull(answered, "the period of an answered close");
            if (periods.markClosed(period.start(), closed.closedAt(), closed.entryCount()) == 1) {
                log.info("Period {}: closed with {} transfers, draining", period.start(), closed.entryCount());
            }
        } else if (refusedWith(reply, HttpStatus.NOT_FOUND)) {
            failed(period, PeriodFailure.PERIOD_UNKNOWN_TO_BANK, null, now, "gateway close: " + summary(reply));
        } else if (pastDeadline(period, now)) {
            failed(period, PeriodFailure.PERIOD_NOT_CLOSED, null, now, "gateway close: " + summary(reply));
        }
    }

    private void drain(BankPeriod period, Instant now) throws InterruptedException {
        var start = period.start();
        var pastDeadline = pastDeadline(period, now);
        var summaryReply = gateway.summary(start);
        var ledgerReply = ledger.reconciliation(start);
        if (refusedWith(summaryReply, HttpStatus.NOT_FOUND) || refusedWith(ledgerReply, HttpStatus.NOT_FOUND)) {
            failed(period, PeriodFailure.PERIOD_UNKNOWN_TO_BANK, null, now, "gateway: " + summary(summaryReply) + ", ledger: " + summary(ledgerReply));
            return;
        }
        if (!(summaryReply instanceof Reply.Answered<PeriodSummary>(var summaryCode, var answeredSummary))
            || !(ledgerReply instanceof Reply.Answered<LedgerReconciliation>(var ledgerCode, var answeredLedger))) {
            if (pastDeadline) {
                failed(period, PeriodFailure.BANK_UNAVAILABLE, null, now, "gateway: " + summary(summaryReply) + ", ledger: " + summary(ledgerReply));
            }
            return;
        }
        var bank = Objects.requireNonNull(answeredSummary, "the summary of an answered call");
        var inLedger = Objects.requireNonNull(answeredLedger, "the reconciliation of an answered call");
        // Both counts only grow after the close, so their sum grows while either side still moves.
        var progress = inLedger.postedTransfers() + inLedger.rejectedTransfers() + bank.posted() + bank.rejected();
        var progressAt = periods.recordDrainProgress(start, progress, now) == 1
            ? now
            : Objects.requireNonNull(period.drainProgressAt(), "drainProgressAt of a period whose progress was recorded");
        var drained = Verdicts.drained(bank, inLedger);
        if (drained && period.booksFinalAt() == null) {
            periods.markBooksFinal(start, now);
        }
        var stalled = !now.isBefore(progressAt.plus(props.drainStallTimeout()));
        if (!drained && !Verdicts.overcounted(bank, inLedger) && !stalled && !pastDeadline) {
            return;
        }
        if (drained && !Verdicts.reasonsSettled(bank) && !pastDeadline) {
            return;
        }
        var check = inLedger.check();
        if (check == null && due(period.checkRequestedAt(), now)) {
            periods.markCheckRequested(start, now);
            ledger.requestCheck(start);
        }
        var clientReply = generator.reconciliation(start);
        if (refusedWith(clientReply, HttpStatus.NOT_FOUND)) {
            failed(period, PeriodFailure.PERIOD_UNKNOWN_TO_BANK, Verdicts.numbersOf(bank, inLedger, null), now, "generator: " + summary(clientReply));
            return;
        }
        var client = clientReport(period, clientReply, now);
        if (check == null || client == null) {
            if (pastDeadline) {
                PeriodFailure failure;
                if (!drained) {
                    failure = PeriodFailure.DRAIN_TIMED_OUT;
                } else if (check == null) {
                    failure = PeriodFailure.LEDGER_CHECK_UNAVAILABLE;
                } else {
                    failure = PeriodFailure.CLIENT_RECONCILIATION_UNAVAILABLE;
                }
                failed(
                    period, failure, Verdicts.numbersOf(bank, inLedger, null), now,
                    "accepted " + bank.accepted() + ", ledger applied " + (inLedger.postedTransfers() + inLedger.rejectedTransfers())
                        + ", check " + (check == null ? "missing" : "made") + ", generator: " + summary(clientReply));
            }
            return;
        }
        var stats = reporting.stats(start) instanceof Reply.Answered<PeriodStats>(var statsCode, var answeredStats) ? answeredStats : null;
        var catchingUp = drained && !pastDeadline && now.isBefore(progressAt.plus(reportingProps.catchUpWait()));
        if (catchingUp && (stats == null || !Verdicts.projectionMatchesBooks(stats, bank, inLedger))) {
            return;
        }
        if (!(ledger.books() instanceof Reply.Answered<Books>(var booksCode, var allBalances)) || allBalances == null) {
            if (pastDeadline) {
                failed(period, PeriodFailure.BOOKS_UNAVAILABLE, Verdicts.numbersOf(bank, inLedger, stats), now, "ledger books not read");
            }
            return;
        }
        var entryCount = Objects.requireNonNull(period.entryCount(), "entryCount of a closed period");
        var report = Verdicts.verdictOf(entryCount, bank, inLedger, check, allBalances, client, stats, chaos.between(start, period.end()));
        if (periods.markReconciled(start, report, Verdicts.numbersOf(bank, inLedger, stats), now) == 1) {
            log.info("Period {}: reconciled, {} {}", start, report.verdict(), report.mismatches());
        }
    }

    /** Null while the clients' reconciliation is not there; it is asked for again after a while, as a restarted generator forgets it. */
    private @Nullable ClientReconciliationReport clientReport(BankPeriod period, Reply<ClientReconciliation> reply, Instant now)
        throws InterruptedException {
        if (!(reply instanceof Reply.Answered<ClientReconciliation>(var code, var answered))) {
            return null;
        }
        var reconciliation = Objects.requireNonNull(answered, "the reconciliation of an answered call");
        if (reconciliation.state() == ClientReconciliationState.DONE) {
            return Objects.requireNonNull(reconciliation.report(), "the report of a finished reconciliation");
        }
        if (reconciliation.state() != ClientReconciliationState.RUNNING && due(period.clientRequestedAt(), now)) {
            periods.markClientRequested(period.start(), now);
            generator.startReconciliation(period.start());
        }
        return null;
    }

    private boolean due(@Nullable Instant requestedAt, Instant now) {
        return requestedAt == null || !now.isBefore(requestedAt.plus(props.requestRetry()));
    }

    private boolean pastDeadline(BankPeriod period, Instant now) {
        var closingStartedAt = Objects.requireNonNull(period.closingStartedAt(), "closingStartedAt of a period past OPEN");
        return !now.isBefore(closingStartedAt.plus(props.drainTimeout()));
    }

    private void failed(BankPeriod period, PeriodFailure failure, @Nullable PeriodNumbers numbers, Instant now, String detail) {
        if (periods.markFailed(period.start(), period.state(), failure, numbers, now) == 1) {
            log.warn("Period {}: failed in {}: {} ({})", period.start(), period.state(), failure, detail);
        }
    }

    private static boolean refusedWith(Reply<?> reply, HttpStatus status) {
        return reply instanceof Reply.Refused<?> refused && refused.status() == status.value();
    }

    private static String summary(Reply<?> reply) {
        return switch (reply) {
            case Reply.Answered<?> answered -> "answered " + answered.status();
            case Reply.Refused<?> refused -> refused.status() + " " + refused.error();
            case Reply.NoAnswer<?> noAnswer -> "NoAnswer";
            case Reply.NotConnected<?> notConnected -> "NotConnected";
            case Reply.Unreadable<?> unreadable -> "Unreadable";
        };
    }
}
