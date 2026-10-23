package com.ledgerlab.control.api

import com.ledgerlab.control.gateway.GatewayWarmUp
import com.ledgerlab.control.metrics.LiveRuns
import com.ledgerlab.control.metrics.LiveView
import com.ledgerlab.control.runs.Conductor
import com.ledgerlab.control.runs.Run
import com.ledgerlab.control.runs.RunReport
import com.ledgerlab.control.runs.RunRequest
import com.ledgerlab.control.runs.RunResult
import com.ledgerlab.control.runs.RunService
import com.ledgerlab.control.runs.RunState
import com.ledgerlab.control.runs.StartAdmission
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

@Schema(description = "A run to start.")
data class StartRunRequest(
    @field:Schema(description = "Idempotency key within the session: a repeat with the same body gets the same run.", requiredMode = Schema.RequiredMode.REQUIRED)
    val requestId: UUID?,
    @field:Schema(description = "Transfers per second, one of the session's tpsOptions.", requiredMode = Schema.RequiredMode.REQUIRED)
    val tps: Int?,
    @field:Schema(description = "Seconds, one of the session's durationOptions.", requiredMode = Schema.RequiredMode.REQUIRED)
    val durationSeconds: Int?,
)

@Schema(description = "A run as control sees it now.")
data class RunView(
    val runId: UUID,
    val state: RunState,
    val tps: Int,
    val durationSeconds: Int,
    val createdAt: Instant,
    @field:Schema(description = "When the run left the queue; null while queued or if it expired.")
    val admittedAt: Instant?,
    @field:Schema(description = "When the run reached a final state; null before.")
    val finishedAt: Instant?,
    @field:Schema(description = "Place in the queue, from 1; only while queued.")
    val position: Long?,
    @field:Schema(description = "WARM_UP while queued because the bank is warming up after a start; null otherwise.")
    val waitingFor: WaitingFor?,
    @field:Schema(description = "When the clients started sending; null before.")
    val startedAt: Instant?,
    @field:Schema(description = "A stop was asked for while the run was running.")
    val stopRequested: Boolean,
    @field:Schema(description = "When the bank closed the run's period, by the bank's clock; null before.")
    val closedAt: Instant?,
    @field:Schema(description = "When control first saw the books final: every accepted transfer applied; reconciling from then on. null before.")
    val booksFinalAt: Instant?,
    @field:Schema(description = "The verdict; only once reconciled. Only BALANCED is green.")
    val result: RunResult?,
    @field:Schema(description = "Why the run failed without a verdict; only when failed.")
    val failure: String?,
    @field:Schema(description = "Live numbers; only in a run's own GET while running or draining, else null. The report keeps the final ones.")
    val live: LiveView?,
)

enum class WaitingFor { WARM_UP }

@Schema(description = "A session's runs, newest first.")
data class RunList(val items: List<RunView>)

@RestController
@RequestMapping(RunController.RUNS_PATH)
class RunController(
    private val runs: RunService,
    private val starts: StartAdmission,
    private val liveRuns: LiveRuns,
    private val conductor: Conductor,
    private val gatewayWarmUp: GatewayWarmUp,
) {

    @Operation(summary = "Start a run: it joins the queue")
    @ApiResponse(responseCode = "200", description = "A repeat of a run that is final", headers = [Header(name = HttpHeaders.LOCATION, description = RUN_LOCATION, schema = Schema(type = "string"))])
    @ApiResponse(responseCode = "202", description = "Queued, or a repeat of a run that is not final", headers = [Header(name = HttpHeaders.LOCATION, description = RUN_LOCATION, schema = Schema(type = "string"))])
    @ApiResponse(responseCode = "400", description = "Unreadable body, no requestId, or tps or duration not allowed", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "403", description = "The invite expired or was switched off", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "404", description = "Unknown session", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "422", description = "The requestId was used with a different body", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "409", description = "Another run of the session is not final", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(
        responseCode = "429", description = "Quota used up (Retry-After says when) or queue full",
        headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds until the quota allows a run; absent when the queue is full", schema = Schema(type = "integer"))],
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @ApiResponse(
        responseCode = "503", description = "Too many starts at once; nothing was started, start again after Retry-After",
        headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait", schema = Schema(type = "integer"))],
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @PostMapping
    fun start(@PathVariable sessionId: UUID, @RequestBody request: StartRunRequest): ResponseEntity<RunView> {
        val run = starts.admit { runs.start(sessionId, RunRequest(request.requestId, request.tps, request.durationSeconds)) }
        return ResponseEntity.status(if (run.state.isFinal) HttpStatus.OK else HttpStatus.ACCEPTED)
            .location(URI.create("${RUNS_PATH.replace("{sessionId}", sessionId.toString())}/${run.runId}"))
            .body(run.view())
    }

    @Operation(summary = "A session's runs")
    @ApiResponse(responseCode = "200", description = "The runs, newest first")
    @ApiResponse(responseCode = "404", description = "Unknown session", content = [Content(schema = Schema(implementation = ApiError::class))])
    @GetMapping
    fun list(@PathVariable sessionId: UUID): RunList = RunList(runs.listOf(sessionId).map { it.view() })

    @Operation(summary = "A run of the session")
    @ApiResponse(responseCode = "200", description = "The run")
    @ApiResponse(responseCode = "404", description = "Unknown session, or no such run in it", content = [Content(schema = Schema(implementation = ApiError::class))])
    @GetMapping("/{runId}")
    fun get(@PathVariable sessionId: UUID, @PathVariable runId: UUID): RunView {
        val run = runs.find(sessionId, runId)
        val live = liveRuns.of(runId)?.takeIf { run.state == RunState.RUNNING || run.state == RunState.DRAINING }
        return run.view(live?.copy(chaos = conductor.chaosSince(run)))
    }

    @Operation(summary = "Stop a running run early; the bank still finishes and reconciles it")
    @ApiResponse(responseCode = "200", description = "The run is past running; nothing to stop")
    @ApiResponse(responseCode = "202", description = "Stopping")
    @ApiResponse(responseCode = "404", description = "Unknown session, or no such run in it", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "409", description = "The run has not started yet", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping("/{runId}/stop")
    fun stop(@PathVariable sessionId: UUID, @PathVariable runId: UUID): ResponseEntity<RunView> {
        val run = runs.stop(sessionId, runId)
        return ResponseEntity.status(if (run.state == RunState.RUNNING) HttpStatus.ACCEPTED else HttpStatus.OK).body(run.view())
    }

    @Operation(summary = "The verdict of a reconciled run")
    @ApiResponse(responseCode = "200", description = "The report")
    @ApiResponse(responseCode = "404", description = "Unknown session, or no such run in it", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "409", description = "The run is not reconciled", content = [Content(schema = Schema(implementation = ApiError::class))])
    @GetMapping("/{runId}/report")
    fun report(@PathVariable sessionId: UUID, @PathVariable runId: UUID): RunReport = runs.report(sessionId, runId)

    private fun Run.view(live: LiveView? = null) =
        toView(live, if (state == RunState.QUEUED && !gatewayWarmUp.warm) WaitingFor.WARM_UP else null)

    companion object {
        const val RUNS_PATH = "/api/sessions/{sessionId}/runs"
        private const val RUN_LOCATION = "The run's address"
    }
}

private fun Run.toView(live: LiveView?, waitingFor: WaitingFor?) = RunView(
    runId, state, tps, durationSeconds, createdAt, admittedAt, finishedAt, position, waitingFor, startedAt, stopRequested, closedAt, booksFinalAt,
    result, failure, live,
)
