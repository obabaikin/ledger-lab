package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/simulations")
public class SimulationController {

    private static final int DEFAULT_PAGE = 100;
    private static final int MAX_PAGE = 1_000;

    /** Time for a request that has just timed out to finish its thread. */
    private static final Duration STOP_GRACE_MARGIN = Duration.ofSeconds(1);

    private final GatewayClient client;
    private final GeneratorProperties props;
    private final MeterRegistry registry;
    private final ConcurrentHashMap<UUID, Simulation> simulations = new ConcurrentHashMap<>();

    public SimulationController(GatewayClient client, GeneratorProperties props, MeterRegistry registry) {
        this.client = client;
        this.props = props;
        this.registry = registry;
    }

    @ApiResponse(responseCode = "201", description = "Started")
    @ApiResponse(responseCode = "400", description = "Invalid request, longer than the maximum duration, or the run alone exceeds the journal cap", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Already running, or journals in memory leave no room", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SimulationStatus start(@Valid @RequestBody StartSimulationRequest request) {
        var runId = Objects.requireNonNull(request.runId(), "runId of a validated request");
        var duration = Duration.ofSeconds(request.durationSeconds());
        if (duration.compareTo(props.maxDuration()) > 0) {
            throw new IllegalArgumentException("durationSeconds must be at most " + props.maxDuration().toSeconds());
        }
        var capacity = Simulation.journalCapacity(request.tps(), duration);
        if (capacity > props.journal().maxEntries()) {
            throw new IllegalArgumentException(
                "tps x durationSeconds = " + capacity + " exceeds the journal cap of " + props.journal().maxEntries());
        }
        // The capacity check and the insert must not interleave with another start.
        synchronized (simulations) {
            var existing = simulations.get(runId);
            if (existing != null && existing.state().isActive()) {
                throw new SimulationAlreadyRunningException(runId);
            }
            var reservedByOtherRuns = simulations.entrySet().stream()
                .filter(it -> !it.getKey().equals(runId) && it.getValue().journal() != null)
                .mapToLong(it -> it.getValue().journalCapacity())
                .sum();
            if (reservedByOtherRuns + capacity > props.journal().maxEntries()) {
                throw new JournalCapacityException(
                    "journals in memory hold " + reservedByOtherRuns + " of " + props.journal().maxEntries()
                        + " entries; retry after older journals are released");
            }
            var profile = new LoadProfile(
                runId, request.accountCount(), request.blockedAccounts(), request.hotAccounts(),
                props.profile().oversizedShare(), props.profile().blockedShare());
            var simulation = new Simulation(
                runId, request.tps(), duration, profile,
                request.waitShare(), Duration.ofSeconds(request.waitTimeoutSeconds()),
                stopGrace(request.waitTimeoutSeconds()), client, props.maxInFlight(), registry,
                props.profile().duplicateShare(), props.profile().duplicateDelays());
            simulation.start();
            simulations.put(runId, simulation);
            return simulation.status();
        }
    }

    /** Finished simulations stay readable for a while, then go, so a long-lived pod does not grow forever. */
    @Scheduled(fixedDelayString = "${generator.retention-sweep-interval}")
    public void evictFinished() {
        var now = Instant.now();
        var statusesUntil = now.minus(props.retention());
        var journalsUntil = now.minus(props.journal().retention());
        synchronized (simulations) {
            simulations.entrySet().removeIf(it -> it.getValue().finishedBefore(statusesUntil));
            simulations.values().stream()
                .filter(it -> it.journal() != null && it.finishedBefore(journalsUntil))
                .forEach(Simulation::releaseJournal);
        }
    }

    @ApiResponse(responseCode = "200", description = "The simulation's state and counts")
    @ApiResponse(responseCode = "404", description = "Unknown run, or evicted after the retention", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{runId}")
    public SimulationStatus status(@PathVariable UUID runId) {
        return simulationOf(runId).status();
    }

    @GetMapping("/{runId}/transfers/{transferId}")
    public JournalEntryView transfer(@PathVariable UUID runId, @PathVariable UUID transferId) {
        var entry = journalOf(simulationOf(runId)).get(transferId);
        if (entry == null) {
            throw new NotFoundException("No transfer " + transferId + " in the journal of simulation " + runId);
        }
        return entry;
    }

    /** Pages in the order of first send; while the run is active {@link JournalPage#next} is always set, as entries keep coming. */
    @GetMapping("/{runId}/transfers")
    public JournalPage transfers(
        @PathVariable UUID runId,
        @RequestParam(required = false) @Nullable JournalOutcome outcome,
        @RequestParam(required = false) @Nullable Boolean resent,
        @RequestParam(required = false) @Nullable RejectReason rejectReason,
        @RequestParam(defaultValue = "0") long after,
        @RequestParam(required = false) @Nullable Integer limit
    ) {
        var pageSize = limit != null ? limit : DEFAULT_PAGE;
        if (pageSize < 1 || pageSize > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
        }
        if (after < 0) {
            throw new IllegalArgumentException("after must not be negative");
        }
        var simulation = simulationOf(runId);
        // Read before the page: a run that is already over adds no entries, so the page may say there is no next one.
        var complete = !simulation.state().isActive();
        return journalOf(simulation).page(new JournalFilter(outcome, resent, rejectReason), after, pageSize, complete);
    }

    @ApiResponse(responseCode = "200", description = "The report")
    @ApiResponse(responseCode = "404", description = "Unknown run, or its journal released", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "With error \"" + GeneratorErrors.RUN_STILL_ACTIVE + "\": the run's clients are still sending or stopping; else the period is not closed", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "The gateway's statement is inconsistent or unreadable", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "The gateway did not answer", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{runId}/reconciliation")
    public ClientReconciliationReport reconciliation(@PathVariable UUID runId) {
        var simulation = simulationOf(runId);
        if (simulation.state().isActive()) {
            throw new RunStillActiveException();
        }
        var reconciliation = new ClientReconciliation(runId, journalOf(simulation), simulation.status().refused());
        var cursors = new HashSet<UUID>();
        Long entryCount = null;
        UUID after = null;
        do {
            var page = client.statement(runId, after);
            if (entryCount != null && page.entryCount() != entryCount) {
                throw new BadGatewayAnswerException(
                    "The statement of run " + runId + " changed its totals between pages: entryCount " + entryCount + ", then " + page.entryCount());
            }
            entryCount = page.entryCount();
            for (var line : page.items()) {
                if (!reconciliation.add(line)) {
                    throw new BadGatewayAnswerException("The statement of run " + runId + " lists transfer " + line.transferId() + " twice");
                }
            }
            after = page.next();
            if (after != null && !cursors.add(after)) {
                throw new BadGatewayAnswerException("The statement of run " + runId + " pages back to " + after);
            }
        } while (after != null);
        var report = reconciliation.report();
        if (report.statement().rows() != entryCount) {
            throw new BadGatewayAnswerException(
                "The statement of run " + runId + " is incomplete: " + report.statement().rows() + " lines, entryCount " + entryCount);
        }
        return report;
    }

    @ApiResponse(responseCode = "204", description = "Released; the status stays")
    @ApiResponse(responseCode = "404", description = "Unknown run, or its journal already released", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "The run is still active", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/{runId}/journal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseJournal(@PathVariable UUID runId) {
        synchronized (simulations) {
            var simulation = simulationOf(runId);
            if (simulation.state().isActive()) {
                throw new RunStillActiveException();
            }
            journalOf(simulation);
            simulation.releaseJournal();
        }
    }

    private Simulation simulationOf(UUID runId) {
        var simulation = simulations.get(runId);
        if (simulation == null) {
            throw new NotFoundException("No simulation " + runId);
        }
        return simulation;
    }

    private static Journal journalOf(Simulation simulation) {
        var journal = simulation.journal();
        if (journal == null) {
            throw new NotFoundException("The journal of simulation " + simulation.runId() + " has been released");
        }
        return journal;
    }

    /**
     * Starts a graceful stop. 202 while the run is still stopping: poll the status until it is no
     * longer active. 200 if the run has already ended, on its own or by an earlier stop.
     */
    @ApiResponse(responseCode = "200", description = "Already ended")
    @ApiResponse(responseCode = "202", description = "Stopping; poll the status")
    @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{runId}/stop")
    public ResponseEntity<SimulationStatus> stop(@PathVariable UUID runId) {
        var simulation = simulationOf(runId);
        simulation.stop();
        var status = simulation.status();
        return status.state().isActive() ? ResponseEntity.accepted().body(status) : ResponseEntity.ok(status);
    }

    /**
     * The slowest open request ends by its own timeouts: connecting, then the wait plus the
     * request timeout. No retry starts after a stop, so backoffs only need the margin.
     */
    private Duration stopGrace(int waitTimeoutSeconds) {
        return props.connectTimeout().plus(Duration.ofSeconds(waitTimeoutSeconds)).plus(props.requestTimeout()).plus(STOP_GRACE_MARGIN);
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError conflict(ConflictException e) {
        return new ApiError(e.getMessage() != null ? e.getMessage() : "conflict");
    }

    @ExceptionHandler(GatewayUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError gatewayUnavailable(GatewayUnavailableException e) {
        return new ApiError(e.getMessage() != null ? e.getMessage() : "gateway unavailable");
    }

    @ExceptionHandler(BadGatewayAnswerException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiError gatewayAnswer(BadGatewayAnswerException e) {
        return new ApiError(e.getMessage() != null ? e.getMessage() : "bad gateway");
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError notFound(NotFoundException e) {
        return new ApiError(e.getMessage() != null ? e.getMessage() : "not found");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalid(IllegalArgumentException e) {
        return new ApiError(e.getMessage() != null ? e.getMessage() : "bad request");
    }
}
