package com.ledgerlab.control.api

import com.ledgerlab.control.runs.Run
import com.ledgerlab.control.runs.RunRequest
import com.ledgerlab.control.runs.RunService
import com.ledgerlab.control.runs.RunState
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
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
    @field:Schema(description = "Idempotency key within the session: a repeat with the same body gets the same run.")
    @field:NotNull val requestId: UUID?,
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
)

@Schema(description = "A session's runs, newest first.")
data class RunList(val items: List<RunView>)

@RestController
@RequestMapping(RunController.RUNS_PATH)
class RunController(private val runs: RunService) {

    @Operation(summary = "Start a run: it joins the queue")
    @ApiResponse(responseCode = "200", description = "A repeat of a run that is final")
    @ApiResponse(responseCode = "202", description = "Queued, or a repeat of a run that is not final")
    @ApiResponse(responseCode = "400", description = "Invalid body, tps or duration", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "403", description = "The invite expired or was switched off", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "404", description = "Unknown session", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "422", description = "The requestId was used with a different body", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "429", description = "Quota used up (Retry-After says when) or queue full", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping
    fun start(@PathVariable sessionId: UUID, @Valid @RequestBody request: StartRunRequest): ResponseEntity<RunView> {
        val run = runs.start(sessionId, RunRequest(request.requestId!!, request.tps, request.durationSeconds))
        return ResponseEntity.status(if (run.state.isFinal) HttpStatus.OK else HttpStatus.ACCEPTED)
            .location(URI.create("${RUNS_PATH.replace("{sessionId}", sessionId.toString())}/${run.runId}"))
            .body(run.toView())
    }

    @Operation(summary = "A session's runs")
    @ApiResponse(responseCode = "200", description = "The runs, newest first")
    @ApiResponse(responseCode = "404", description = "Unknown session", content = [Content(schema = Schema(implementation = ApiError::class))])
    @GetMapping
    fun list(@PathVariable sessionId: UUID): RunList = RunList(runs.listOf(sessionId).map { it.toView() })

    @Operation(summary = "A run of the session")
    @ApiResponse(responseCode = "200", description = "The run")
    @ApiResponse(responseCode = "404", description = "Unknown session, or no such run in it", content = [Content(schema = Schema(implementation = ApiError::class))])
    @GetMapping("/{runId}")
    fun get(@PathVariable sessionId: UUID, @PathVariable runId: UUID): RunView = runs.find(sessionId, runId).toView()

    companion object {
        const val RUNS_PATH = "/api/sessions/{sessionId}/runs"
    }
}

private fun Run.toView() = RunView(runId, state, tps, durationSeconds, createdAt, admittedAt, finishedAt, position)
