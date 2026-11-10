package com.ledgerlab.control.api;

import com.ledgerlab.control.metrics.LiveRuns;
import com.ledgerlab.control.metrics.LiveView;
import com.ledgerlab.control.runs.Conductor;
import com.ledgerlab.control.runs.QueueScheduler;
import com.ledgerlab.control.runs.Run;
import com.ledgerlab.control.runs.RunReport;
import com.ledgerlab.control.runs.RunRequest;
import com.ledgerlab.control.runs.RunService;
import com.ledgerlab.control.runs.RunState;
import com.ledgerlab.control.runs.StartAdmission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RunController.RUNS_PATH)
public class RunController {

    public static final String RUNS_PATH = "/api/sessions/{sessionId}/runs";
    private static final String RUN_LOCATION = "The run's address";
    private static final String SESSION_ID_VARIABLE = "{sessionId}";

    private final RunService runs;
    private final StartAdmission starts;
    private final LiveRuns liveRuns;
    private final Conductor conductor;
    private final QueueScheduler queue;

    public RunController(RunService runs, StartAdmission starts, LiveRuns liveRuns, Conductor conductor, QueueScheduler queue) {
        this.runs = runs;
        this.starts = starts;
        this.liveRuns = liveRuns;
        this.conductor = conductor;
        this.queue = queue;
    }

    @Operation(summary = "Start a run: it joins the queue")
    @ApiResponse(responseCode = "200", description = "A repeat of a run that is final", headers = @Header(name = HttpHeaders.LOCATION, description = RUN_LOCATION, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "202", description = "Queued, or a repeat of a run that is not final", headers = @Header(name = HttpHeaders.LOCATION, description = RUN_LOCATION, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Unreadable body, no requestId, or tps or duration not allowed", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "The invite expired or was switched off", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "The requestId was used with a different body", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Another run of the session is not final", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
        responseCode = "429", description = "Quota used up (Retry-After says when) or queue full",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds until the quota allows a run; absent when the queue is full", schema = @Schema(type = "integer")),
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
        responseCode = "503", description = "Too many starts at once; nothing was started, start again after Retry-After",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait", schema = @Schema(type = "integer")),
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    public ResponseEntity<RunView> start(@PathVariable UUID sessionId, @RequestBody StartRunRequest request) throws InterruptedException {
        var run = starts.admit(() -> runs.start(sessionId, new RunRequest(request.requestId(), request.tps(), request.durationSeconds())));
        return ResponseEntity.status(run.state().isFinal() ? HttpStatus.OK : HttpStatus.ACCEPTED)
            .location(URI.create(RUNS_PATH.replace(SESSION_ID_VARIABLE, sessionId.toString()) + "/" + run.runId()))
            .body(view(run, null));
    }

    @Operation(summary = "A session's runs")
    @ApiResponse(responseCode = "200", description = "The runs, newest first")
    @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    public RunList list(@PathVariable UUID sessionId) {
        return new RunList(runs.listOf(sessionId).stream().map(run -> view(run, null)).toList());
    }

    @Operation(summary = "A run of the session")
    @ApiResponse(responseCode = "200", description = "The run")
    @ApiResponse(responseCode = "404", description = "Unknown session, or no such run in it", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{runId}")
    public RunView get(@PathVariable UUID sessionId, @PathVariable UUID runId) {
        var run = runs.find(sessionId, runId);
        var live = run.state() == RunState.RUNNING || run.state() == RunState.DRAINING ? liveRuns.of(runId) : null;
        return view(run, live == null ? null : live.withChaos(conductor.chaosSince(run)));
    }

    @Operation(summary = "Stop a running run early; the bank still finishes and reconciles it")
    @ApiResponse(responseCode = "200", description = "The run is past running; nothing to stop")
    @ApiResponse(responseCode = "202", description = "Stopping")
    @ApiResponse(responseCode = "404", description = "Unknown session, or no such run in it", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "The run has not started yet", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{runId}/stop")
    public ResponseEntity<RunView> stop(@PathVariable UUID sessionId, @PathVariable UUID runId) {
        var run = runs.stop(sessionId, runId);
        return ResponseEntity.status(run.state() == RunState.RUNNING ? HttpStatus.ACCEPTED : HttpStatus.OK).body(view(run, null));
    }

    @Operation(summary = "The verdict of a reconciled run")
    @ApiResponse(responseCode = "200", description = "The report")
    @ApiResponse(responseCode = "404", description = "Unknown session, or no such run in it", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "The run is not reconciled", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{runId}/report")
    public RunReport report(@PathVariable UUID sessionId, @PathVariable UUID runId) {
        return runs.report(sessionId, runId);
    }

    private RunView view(Run run, @Nullable LiveView live) {
        return RunView.of(run, live, run.state() == RunState.QUEUED && !queue.bankReady() ? WaitingFor.WARM_UP : null);
    }
}
