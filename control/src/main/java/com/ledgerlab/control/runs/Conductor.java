package com.ledgerlab.control.runs;

import static com.ledgerlab.control.runs.Verdicts.drainFinished;
import static com.ledgerlab.control.runs.Verdicts.overcounted;
import static com.ledgerlab.control.runs.Verdicts.projectionMatchesBooks;
import static com.ledgerlab.control.runs.Verdicts.verdictOf;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.chaos.ChaosRepository;
import com.ledgerlab.control.gateway.ClosedPeriod;
import com.ledgerlab.control.gateway.GatewayClient;
import com.ledgerlab.control.gateway.RunSummary;
import com.ledgerlab.control.generator.ClientReconciliationReport;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.generator.SimulationStatus;
import com.ledgerlab.control.generator.StartSimulationRequest;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.ledger.LedgerClient;
import com.ledgerlab.control.ledger.OpenAccountsRequest;
import com.ledgerlab.control.ledger.ReconciliationReport;
import com.ledgerlab.control.metrics.LiveRuns;
import com.ledgerlab.control.reporting.ReportingClient;
import com.ledgerlab.control.reporting.RunStats;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class Conductor {

    /** A 502 means the generator could not trust the gateway's statement: reported with its words, not waited on. */
    private static final Set<Integer> CLIENT_RECONCILIATION_FINAL = Set.of(HttpStatus.BAD_GATEWAY.value());
    private static final Set<RunState> LIVE_STATES = Set.of(RunState.RUNNING, RunState.DRAINING);

    private static final Logger log = LoggerFactory.getLogger(Conductor.class);

    private final RunRepository runs;
    private final LedgerClient ledger;
    private final GatewayClient gateway;
    private final GeneratorClient generator;
    private final ReportingClient reporting;
    private final LiveRuns liveRuns;
    private final ChaosRepository chaos;
    private final ControlProperties props;
    /** The last answers to a run's setup, for the log line if the setup times out; lost on a restart, as only the log needs them. */
    private final ConcurrentHashMap<UUID, String> setupReplies = new ConcurrentHashMap<>();

    public Conductor(
        RunRepository runs,
        LedgerClient ledger,
        GatewayClient gateway,
        GeneratorClient generator,
        ReportingClient reporting,
        LiveRuns liveRuns,
        ChaosRepository chaos,
        ControlProperties props
    ) {
        this.runs = runs;
        this.ledger = ledger;
        this.gateway = gateway;
        this.generator = generator;
        this.reporting = reporting;
        this.liveRuns = liveRuns;
        this.chaos = chaos;
        this.props = props;
    }

    /** Runs step side by side, so a slow call of one run does not eat into another run's deadline. */
    public void tick() {
        var inProgress = runs.inProgress();
        try (var steps = Executors.newVirtualThreadPerTaskExecutor()) {
            steps.execute(() -> refreshLive(inProgress));
            inProgress.forEach(run -> steps.execute(() -> step(run)));
        }
    }

    private void refreshLive(List<Run> inProgress) {
        try {
            liveRuns.refresh(inProgress.stream().filter(it -> LIVE_STATES.contains(it.state())).map(Run::runId).toList());
        } catch (Exception e) {
            log.error("Live numbers not read, retried on the next tick", e);
        }
    }

    private void step(Run run) {
        try {
            switch (run.state()) {
                case CREATED -> setUp(run, runs.now());
                case RUNNING -> watch(run, runs.now());
                case DRAINING -> drain(run, runs.now());
                default -> {
                }
            }
        } catch (RuntimeException e) {
            log.error("Run {}: step in {} failed, retried on the next tick", run.runId(), run.state(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void setUp(Run run, Instant now) throws InterruptedException {
        var status = generator.status(run.runId());
        // A start that reached the generator before a restart of control must not be sent again: it would replay the run.
        if (status instanceof Reply.Answered<SimulationStatus>) {
            started(run);
            return;
        }
        // From the first attempt, not from the admission: a long tick of other runs must not use up this run's time.
        var setupStartedAt = run.setupStartedAt() != null ? run.setupStartedAt() : runs.markSetupStarted(run.runId());
        var timedOut = !now.isBefore(setupStartedAt.plus(props.runs().setupTimeout()));
        if (timedOut) {
            // A start whose answer was lost may have reached the generator: its clients would send for a period nobody closes.
            if (run.startSent()) {
                stopClients(run);
            }
            var earlier = setupReplies.getOrDefault(run.runId(), "ledger not asked since control started");
            failed(run, RunOutcomes.SETUP_TIMED_OUT, "generator status: " + summary(status) + "; " + earlier);
            return;
        }
        if (!refusedWith(status, HttpStatus.NOT_FOUND)) {
            return;
        }
        // A start that may have arrived but is unknown now means the generator restarted: the run goes on as a lost client.
        if (run.startSent()) {
            started(run);
            return;
        }
        var accounts = props.runs().accounts();
        var opened = ledger.openAccounts(
            run.runId(),
            new OpenAccountsRequest(accounts.count(), accounts.openingBalanceCents(), accounts.blocked(), accounts.dailyLimitCents()));
        setupReplies.put(run.runId(), "ledger accounts: " + summary(opened));
        var alreadyOpened = refusedWith(opened, HttpStatus.CONFLICT);
        if (refusedWith(opened, HttpStatus.BAD_REQUEST)) {
            failed(run, RunOutcomes.SETUP_REJECTED, null);
            return;
        }
        if (!(opened instanceof Reply.Answered<Object>) && !alreadyOpened) {
            return;
        }
        runs.markStartSent(run.runId(), true);
        var start = generator.start(
            new StartSimulationRequest(run.runId(), run.tps(), run.durationSeconds(), accounts.count(), accounts.blocked()));
        setupReplies.put(run.runId(), "ledger accounts: " + summary(opened) + "; generator start: " + summary(start));
        if (start instanceof Reply.Answered<Object>) {
            started(run);
        } else if (refusedWith(start, HttpStatus.BAD_REQUEST)) {
            failed(run, RunOutcomes.SETUP_REJECTED, null);
        } else if (start instanceof Reply.NotConnected<Object> || (start instanceof Reply.Refused<Object> && !start.isTransient())) {
            runs.markStartSent(run.runId(), false);
        }
    }

    private void stopClients(Run run) throws InterruptedException {
        var reply = generator.stop(run.runId());
        var stopped = reply instanceof Reply.Answered<Object> || refusedWith(reply, HttpStatus.NOT_FOUND);
        if (!stopped) {
            log.warn("Run {}: clients not stopped after the failed setup ({})", run.runId(), summary(reply));
        }
    }

    private void started(Run run) {
        setupReplies.remove(run.runId());
        if (runs.markStarted(run.runId()) == 1) {
            log.info("Run {}: started", run.runId());
        }
    }

    private void failed(Run run, String failure, @Nullable String replies) {
        setupReplies.remove(run.runId());
        if (runs.markFailed(run.runId(), run.state(), failure) == 1) {
            log.warn("Run {}: failed in {}: {}{}", run.runId(), run.state(), failure, replies == null ? "" : " (" + replies + ")");
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

    private void watch(Run run, Instant now) throws InterruptedException {
        if (run.stopRequested()) {
            generator.stop(run.runId());
        }
        var status = generator.status(run.runId());
        var clientLost = refusedWith(status, HttpStatus.NOT_FOUND);
        var clientDone = status instanceof Reply.Answered<SimulationStatus>(var code, var simulation)
            && !Objects.requireNonNull(simulation, "the status of an answered call").state().isActive();
        var startedAt = Objects.requireNonNull(run.startedAt(), "startedAt of a running run");
        var overdue = !now.isBefore(startedAt.plus(Duration.ofSeconds(run.durationSeconds())).plus(props.runs().runningGrace()));
        if (overdue && !clientDone && !clientLost) {
            generator.stop(run.runId());
        }
        if (clientDone || clientLost || overdue) {
            if (runs.startDraining(run.runId(), clientLost) == 1) {
                log.info("Run {}: draining{}", run.runId(), clientLost ? ", client lost" : "");
            }
        }
    }

    private void drain(Run run, Instant now) throws InterruptedException {
        var deadline = Objects.requireNonNull(run.drainStartedAt(), "drainStartedAt of a draining run").plus(props.runs().drainTimeout());
        var pastDeadline = !now.isBefore(deadline);
        if (run.closedAt() == null) {
            if (gateway.close(run.runId()) instanceof Reply.Answered<ClosedPeriod>(var code, var closed)) {
                runs.markClosed(run.runId(), Objects.requireNonNull(closed, "the period of an answered close").closedAt());
            } else if (pastDeadline) {
                failed(run, RunOutcomes.PERIOD_NOT_CLOSED, null);
            }
            return;
        }
        var summary = gateway.summary(run.runId());
        var books = ledger.reconciliation(run.runId());
        if (!(summary instanceof Reply.Answered<RunSummary>(var summaryCode, var answeredSummary))
            || !(books instanceof Reply.Answered<ReconciliationReport>(var booksCode, var answeredBooks))) {
            if (pastDeadline) {
                failed(run, RunOutcomes.BANK_UNAVAILABLE, "gateway: " + summary(summary) + ", ledger: " + summary(books));
            }
            return;
        }
        var bank = Objects.requireNonNull(answeredSummary, "the summary of an answered call");
        var ledgerReport = Objects.requireNonNull(answeredBooks, "the books of an answered call");
        // Both counts only grow after the close, so their sum grows while either side still moves.
        var progress = ledgerReport.postedTransfers() + ledgerReport.rejectedTransfers() + bank.posted() + bank.rejected();
        var progressAt = runs.recordDrainProgress(run.runId(), progress) == 1
            ? now
            : Objects.requireNonNull(run.drainProgressAt(), "drainProgressAt of a closed run");
        var drained = drainFinished(bank, ledgerReport);
        if (drained && run.booksFinalAt() == null) {
            runs.markBooksFinal(run.runId());
        }
        var stalled = !now.isBefore(progressAt.plus(props.runs().drainStallTimeout()));
        if (!drained && !overcounted(bank, ledgerReport) && !stalled && !pastDeadline) {
            return;
        }
        var stats = reporting.finalStats(run.runId()) instanceof Reply.Answered<RunStats>(var statsCode, var answeredStats) ? answeredStats : null;
        var catchingUp = drained && !pastDeadline && now.isBefore(progressAt.plus(props.reporting().catchUpWait()));
        if (catchingUp && (stats == null || !projectionMatchesBooks(stats, bank, ledgerReport))) {
            return;
        }
        var client = clientSide(run, pastDeadline);
        if (client == null) {
            return;
        }
        reconciled(run, bank, ledgerReport, client, stats);
    }

    /** Null while the client's reconciliation may still come: the generator answered 503, not at all, or is still stopping. */
    private @Nullable ClientSide clientSide(Run run, boolean pastDeadline) throws InterruptedException {
        if (run.clientLost()) {
            return new ClientSide.Missing(RunOutcomes.CLIENT_LOST);
        }
        var reply = generator.reconciliation(run.runId());
        if (reply instanceof Reply.Answered<ClientReconciliationReport>(var code, var report)) {
            return new ClientSide.Reconciled(Objects.requireNonNull(report, "the report of an answered call"));
        }
        if (reply instanceof Reply.Refused<ClientReconciliationReport>(var status, var error)) {
            // The clients of a run stopped past its grace may still be stopping when the bank has drained.
            if (status == HttpStatus.CONFLICT.value() && GeneratorApi.RUN_STILL_ACTIVE.equals(error) && !pastDeadline) {
                return null;
            }
            if (CLIENT_RECONCILIATION_FINAL.contains(status) || !reply.isTransient()) {
                return new ClientSide.Missing(RunOutcomes.clientReconciliationFailed(error));
            }
        }
        return pastDeadline ? new ClientSide.Missing(RunOutcomes.CLIENT_RECONCILIATION_UNAVAILABLE) : null;
    }

    private void reconciled(Run run, RunSummary bank, ReconciliationReport ledgerReport, ClientSide client, @Nullable RunStats stats)
        throws InterruptedException {
        var simulation = generator.status(run.runId()) instanceof Reply.Answered<SimulationStatus>(var code, var status) ? status : null;
        var measurements = new RunMeasurements(
            simulation == null ? null : simulation.waitedLatencyP50Ms(),
            simulation == null ? null : simulation.waitedLatencyP99Ms(),
            stats,
            liveRuns.ledgerDuplicatesAtLeast(run.runId()),
            chaosSince(run));
        var report = verdictOf(bank, ledgerReport, client, measurements);
        if (runs.markReconciled(run.runId(), report) == 1) {
            log.info("Run {}: reconciled, {} {}", run.runId(), report.result(), report.reasons());
            releaseJournal(run);
        }
    }

    /** The report holds what the verdict read, so the journal is not needed any more; the generator's retention frees it otherwise. */
    private void releaseJournal(Run run) throws InterruptedException {
        var reply = generator.releaseJournal(run.runId());
        var released = reply instanceof Reply.Answered<Object> || refusedWith(reply, HttpStatus.NOT_FOUND);
        if (!released) {
            log.warn("Run {}: journal not released ({}), the generator's retention frees it", run.runId(), summary(reply));
        }
    }

    /** From the admission: a crash while the run is set up also hits it. */
    public List<ChaosInRun> chaosSince(Run run) {
        var admittedAt = run.admittedAt() != null ? run.admittedAt() : run.createdAt();
        var startedAt = run.startedAt() != null ? run.startedAt() : admittedAt;
        return chaos.since(admittedAt).stream().map(it -> ChaosInRun.of(it, startedAt)).toList();
    }
}
