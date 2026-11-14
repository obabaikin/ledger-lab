package com.ledgerlab.control.periods;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.DatabaseClock;
import com.ledgerlab.control.availability.AvailabilityRepository;
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
import com.ledgerlab.control.ledger.LedgerCheckState;
import com.ledgerlab.control.ledger.LedgerClient;
import com.ledgerlab.control.ledger.LedgerReconciliation;
import com.ledgerlab.control.reporting.PeriodStats;
import com.ledgerlab.control.reporting.ReportingClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
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
    private final AvailabilityRepository windows;
    private final DatabaseClock clock;
    private final ControlProperties.Periods props;
    private final ControlProperties.Reporting reportingProps;
    private final Map<PeriodCall, Counter> calls = new EnumMap<>(PeriodCall.class);
    /**
     * The period this instance of control is settling. One it finds half settled was left by an instance before it: its
     * limits start again, as the time control was down says nothing about the bank.
     */
    private volatile @Nullable Instant settling;

    public PeriodConductor(
        PeriodRepository periods,
        LedgerClient ledger,
        GatewayClient gateway,
        GeneratorClient generator,
        ReportingClient reporting,
        BankLive live,
        ChaosRepository chaos,
        AvailabilityRepository windows,
        DatabaseClock clock,
        ControlProperties props,
        MeterRegistry registry
    ) {
        this.periods = periods;
        this.ledger = ledger;
        this.gateway = gateway;
        this.generator = generator;
        this.reporting = reporting;
        this.live = live;
        this.chaos = chaos;
        this.windows = windows;
        this.clock = clock;
        this.props = props.periods();
        this.reportingProps = props.reporting();
        for (var call : PeriodCall.values()) {
            calls.put(call, Counter.builder(PeriodCall.METRIC)
                .description("Calls control made to a service about a period")
                .tag(PeriodCall.CALL_TAG, call.tag())
                .register(registry));
        }
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
            periods.openUpTo(now, Period.containing(now, props.length()), props.length());
            period = periods.oldestUnfinished();
            if (period == null) {
                return;
            }
            if (period.state() != PeriodState.OPEN) {
                var takenOver = !period.start().equals(settling);
                // During declared maintenance the bank may be down on purpose: the limits wait and start again after the window.
                if (takenOver || windows.inWindow(now)) {
                    periods.restartLimits(period.start(), now);
                    period = Objects.requireNonNull(periods.find(period.start()), "the period just read");
                }
                if (takenOver) {
                    settling = period.start();
                    log.info("Period {}: taken over in {}, its limits start again", period.start(), period.state());
                }
            }
            switch (period.state()) {
                case OPEN -> open(period, now);
                case CLOSING -> {
                    if (tookNextAction(period, now)) {
                        close(period, now);
                    }
                }
                case DRAINING -> {
                    if (tookNextAction(period, now)) {
                        if (period.gatewayCounts() == null || period.ledgerCounts() == null) {
                            count(period, now);
                        } else {
                            askWhetherChecked(period, now);
                        }
                    }
                }
                default -> {
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Period {}: step failed, retried on the next tick", period == null ? "unknown" : period.start(), e);
        }
    }

    /** Of all instances of control, one takes an action that is due; the action is due again after a wait unless it moves the period on. */
    private boolean tookNextAction(BankPeriod period, Instant now) {
        return periods.takeNextAction(period.start(), period.state(), now, retryAt(period, now)) == 1;
    }

    /** The deadline itself is a time to act at: the period then fails with the reason, without waiting for the next try. */
    private Instant retryAt(BankPeriod period, Instant now) {
        var retry = now.plus(props.retryWait());
        var deadline = deadlineOf(period);
        return deadline.isAfter(now) && deadline.isBefore(retry) ? deadline : retry;
    }

    private void open(BankPeriod period, Instant now) throws InterruptedException {
        if (now.isBefore(period.end().plus(props.closeDelay()))) {
            return;
        }
        if (!now.isBefore(period.start().plus(props.detailsRetention()))) {
            failed(period, PeriodFailure.DETAILS_EXPIRED, null, now, "the bank keeps a period's transfers for " + props.detailsRetention());
            return;
        }
        settling = period.start();
        if (periods.markClosing(period.start(), now, now.plus(props.retryWait())) == 1) {
            close(Objects.requireNonNull(periods.find(period.start()), "the period just marked"), now);
        }
    }

    private void close(BankPeriod period, Instant now) throws InterruptedException {
        var reply = called(PeriodCall.GATEWAY_CLOSE, () -> gateway.close(period.start()));
        if (reply instanceof Reply.Answered<ClosedPeriod>(var code, var answered)) {
            var closed = Objects.requireNonNull(answered, "the period of an answered close");
            if (periods.markClosed(period.start(), closed.closedAt(), closed.entryCount(), period.end().plus(props.settleDelay())) == 1) {
                log.info("Period {}: closed with {} transfers, counted at {}", period.start(), closed.entryCount(), period.end().plus(props.settleDelay()));
            }
        } else if (refusedWith(reply, HttpStatus.NOT_FOUND)) {
            failed(period, PeriodFailure.PERIOD_UNKNOWN_TO_BANK, null, now, "gateway close: " + summary(reply));
        } else if (pastDeadline(period, now)) {
            failed(period, PeriodFailure.PERIOD_NOT_CLOSED, null, now, "gateway close: " + summary(reply));
        }
    }

    /**
     * Each count reads the whole period in the gateway and the ledger, so it is made once the transfers are long written,
     * and again only after a wait while the counts do not agree yet.
     */
    private void count(BankPeriod period, Instant now) throws InterruptedException {
        var start = period.start();
        var pastDeadline = pastDeadline(period, now);
        var summaryReply = called(PeriodCall.GATEWAY_SUMMARY, () -> gateway.summary(start));
        var ledgerReply = called(PeriodCall.LEDGER_RECONCILIATION, () -> ledger.reconciliation(start));
        var stats = stats(start);
        if (refusedWith(summaryReply, HttpStatus.NOT_FOUND) || refusedWith(ledgerReply, HttpStatus.NOT_FOUND)) {
            failed(period, PeriodFailure.PERIOD_UNKNOWN_TO_BANK, numbersFrom(period, stats), now, "gateway: " + summary(summaryReply) + ", ledger: " + summary(ledgerReply));
            return;
        }
        if (!(summaryReply instanceof Reply.Answered<PeriodSummary>(var summaryCode, var answeredSummary))
            || !(ledgerReply instanceof Reply.Answered<LedgerReconciliation>(var ledgerCode, var answeredLedger))) {
            if (pastDeadline) {
                failed(period, PeriodFailure.BANK_UNAVAILABLE, numbersFrom(period, stats), now, "gateway: " + summary(summaryReply) + ", ledger: " + summary(ledgerReply));
            }
            return;
        }
        var bank = Objects.requireNonNull(answeredSummary, "the summary of an answered call");
        var inLedger = Objects.requireNonNull(answeredLedger, "the reconciliation of an answered call");
        // Both counts only grow after the close, so their sum grows while either side still moves.
        var progress = inLedger.postedTransfers() + inLedger.rejectedTransfers() + bank.posted() + bank.rejected();
        var moved = periods.recordDrainProgress(start, progress, now) == 1;
        var drained = Verdicts.drained(bank, inLedger);
        if (drained && period.booksFinalAt() == null) {
            periods.markBooksFinal(start, now);
        }
        if (!drained) {
            PeriodFailure failure;
            if (Verdicts.overcounted(bank, inLedger)) {
                failure = PeriodFailure.MORE_RESULTS_THAN_ACCEPTED;
            } else if (pastDeadline) {
                failure = PeriodFailure.DRAIN_TIMED_OUT;
            } else if (!moved) {
                failure = PeriodFailure.DRAIN_STALLED;
            } else {
                return;
            }
            failed(
                period, failure, Verdicts.numbersOf(bank, inLedger, statsOrLive(start, stats)), now,
                "accepted " + bank.accepted() + ", ledger applied " + (inLedger.postedTransfers() + inLedger.rejectedTransfers())
                    + ", gateway results " + (bank.posted() + bank.rejected()));
            return;
        }
        if (!Verdicts.reasonsSettled(bank) && !pastDeadline) {
            return;
        }
        // The ledger's check is asked for only on books that are final: it is made once and kept, and a transfer booked
        // after it would show as a difference of the next period.
        var askAt = now.plus(props.checkWait());
        if (periods.markCounted(start, bank, inLedger, now, pastDeadline || askAt.isAfter(deadlineOf(period)) ? deadlineOf(period) : askAt) != 1) {
            return;
        }
        called(PeriodCall.LEDGER_REQUEST_CHECK, () -> ledger.requestCheck(start));
        called(PeriodCall.GENERATOR_START_RECONCILIATION, () -> generator.startReconciliation(start));
        log.info("Period {}: counted, the ledger's check and the clients' reconciliation started", start);
        if (pastDeadline) {
            askWhetherChecked(Objects.requireNonNull(periods.find(start), "the period just counted"), now);
        }
    }

    /** Short reads only: the verdict rests on the counts kept, the bank's transfers are not counted again. */
    private void askWhetherChecked(BankPeriod period, Instant now) throws InterruptedException {
        var start = period.start();
        var bank = Objects.requireNonNull(period.gatewayCounts(), "the gateway's counts of a counted period");
        var inLedger = Objects.requireNonNull(period.ledgerCounts(), "the ledger's counts of a counted period");
        var pastDeadline = pastDeadline(period, now);
        var checkReply = called(PeriodCall.LEDGER_CHECK, () -> ledger.check(start));
        if (refusedWith(checkReply, HttpStatus.NOT_FOUND)) {
            failed(period, PeriodFailure.PERIOD_UNKNOWN_TO_BANK, bank, inLedger, null, now, "ledger check: " + summary(checkReply));
            return;
        }
        var check = checkReply instanceof Reply.Answered<LedgerCheckState>(var checkCode, var state) && state != null ? state.check() : null;
        if (check == null) {
            // Asking again is harmless: the ledger does not start a check that is running or kept, and one that restarted forgot it.
            periods.markCheckRequested(start, now);
            called(PeriodCall.LEDGER_REQUEST_CHECK, () -> ledger.requestCheck(start));
        }
        var clientReply = called(PeriodCall.GENERATOR_RECONCILIATION, () -> generator.reconciliation(start));
        if (refusedWith(clientReply, HttpStatus.NOT_FOUND)) {
            failed(period, PeriodFailure.PERIOD_UNKNOWN_TO_BANK, bank, inLedger, null, now, "generator: " + summary(clientReply));
            return;
        }
        var client = clientReport(period, clientReply, now);
        if (check == null || client == null) {
            if (pastDeadline) {
                failed(
                    period, check == null ? PeriodFailure.LEDGER_CHECK_UNAVAILABLE : PeriodFailure.CLIENT_RECONCILIATION_UNAVAILABLE, bank, inLedger, null,
                    now, "check " + (check == null ? "missing" : "made") + ", generator: " + summary(clientReply));
            }
            return;
        }
        var stats = stats(start);
        var progressAt = Objects.requireNonNull(period.drainProgressAt(), "drainProgressAt of a counted period");
        var catchingUp = !pastDeadline && now.isBefore(progressAt.plus(reportingProps.catchUpWait()));
        if (catchingUp && (stats == null || !Verdicts.projectionMatchesBooks(stats, bank, inLedger))) {
            return;
        }
        if (!(called(PeriodCall.LEDGER_BOOKS, ledger::books) instanceof Reply.Answered<Books>(var booksCode, var allBalances)) || allBalances == null) {
            if (pastDeadline) {
                failed(period, PeriodFailure.BOOKS_UNAVAILABLE, bank, inLedger, stats, now, "ledger books not read");
            }
            return;
        }
        var entryCount = Objects.requireNonNull(period.entryCount(), "entryCount of a closed period");
        var report = Verdicts.verdictOf(entryCount, bank, inLedger, check, allBalances, client, stats, chaos.between(start, period.end()));
        if (periods.markReconciled(start, report, Verdicts.numbersOf(bank, inLedger, stats), now) == 1) {
            log.info("Period {}: reconciled, {} {}", start, report.verdict(), report.mismatches());
        }
    }

    /** Null while the clients' reconciliation is not there; one that is not running is started again, as a restarted generator forgets it. */
    private @Nullable ClientReconciliationReport clientReport(BankPeriod period, Reply<ClientReconciliation> reply, Instant now)
        throws InterruptedException {
        if (!(reply instanceof Reply.Answered<ClientReconciliation>(var code, var answered))) {
            return null;
        }
        var reconciliation = Objects.requireNonNull(answered, "the reconciliation of an answered call");
        // A finished reconciliation without its report is waited for like one not finished: the period fails at its limit.
        if (reconciliation.state() == ClientReconciliationState.DONE) {
            return reconciliation.report();
        }
        if (reconciliation.state() != ClientReconciliationState.RUNNING) {
            periods.markClientRequested(period.start(), now);
            called(PeriodCall.GENERATOR_START_RECONCILIATION, () -> generator.startReconciliation(period.start()));
        }
        return null;
    }

    /** From the period's end; a period whose limits started again, after control was down or in a maintenance window, gets the whole span from then. */
    private Instant deadlineOf(BankPeriod period) {
        var closingStartedAt = Objects.requireNonNull(period.closingStartedAt(), "closingStartedAt of a period past OPEN");
        return closingStartedAt.minus(props.closeDelay()).plus(props.verdictDeadline());
    }

    private boolean pastDeadline(BankPeriod period, Instant now) {
        return !now.isBefore(deadlineOf(period));
    }

    private void failed(
        BankPeriod period, PeriodFailure failure, PeriodSummary bank, LedgerReconciliation inLedger, @Nullable PeriodStats stats, Instant now, String detail
    ) throws InterruptedException {
        failed(period, failure, Verdicts.numbersOf(bank, inLedger, stats != null ? stats : lastStats(period)), now, detail);
    }

    /** Without the bank's counts the period keeps the numbers from its events: the bank's totals must not fall when a period fails. */
    private void failed(BankPeriod period, PeriodFailure failure, @Nullable PeriodNumbers counted, Instant now, String detail)
        throws InterruptedException {
        var numbers = counted != null ? counted : numbersFrom(period, lastStats(period));
        if (periods.markFailed(period.start(), period.state(), failure, numbers, now) == 1) {
            log.warn("Period {}: failed in {}: {} ({})", period.start(), period.state(), failure, detail);
        }
    }

    private @Nullable PeriodNumbers numbersFrom(BankPeriod period, @Nullable PeriodStats stats) {
        var known = statsOrLive(period.start(), stats);
        return known == null
            ? null
            : new PeriodNumbers(known.accepted(), known.posted(), known.rejected(), known.postedAmountCents(), known.rejectionsByReason());
    }

    private @Nullable PeriodStats stats(Instant start) throws InterruptedException {
        return called(PeriodCall.REPORTING_STATS, () -> reporting.stats(start)) instanceof Reply.Answered<PeriodStats>(var code, var stats) ? stats : null;
    }

    private @Nullable PeriodStats statsOrLive(Instant start, @Nullable PeriodStats stats) {
        return stats != null ? stats : live.snapshot().stats().get(start);
    }

    private @Nullable PeriodStats lastStats(BankPeriod period) throws InterruptedException {
        return statsOrLive(period.start(), stats(period.start()));
    }

    private <T> Reply<T> called(PeriodCall call, ServiceCall<T> service) throws InterruptedException {
        Objects.requireNonNull(calls.get(call), "a counter for every call").increment();
        return service.call();
    }

    @FunctionalInterface
    private interface ServiceCall<T> {
        Reply<T> call() throws InterruptedException;
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
