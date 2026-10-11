package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StartSimulationRequest(
    @field:NotNull val runId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:Min(1) @field:Max(MAX_TPS) val tps: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:Min(1) @field:Max(MAX_DURATION_SECONDS) val durationSeconds: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:Min(MIN_ACCOUNTS) val accountCount: Int,
    @field:Min(0) val blockedAccounts: Int = 0,
    @field:Min(0) val hotAccounts: Int = 5,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val waitShare: Double = 0.1,
    @field:Min(1) @field:Max(MAX_WAIT_TIMEOUT_SECONDS) val waitTimeoutSeconds: Int = 5,
)

/** Run limits of the public demo (shared vCPUs, a person waiting on the page); raise only with a recorded reason. */
private const val MAX_TPS = 1_000L
private const val MAX_DURATION_SECONDS = 30L
private const val MAX_WAIT_TIMEOUT_SECONDS = 30L
/** A transfer needs a sender and a different receiver. */
private const val MIN_ACCOUNTS = 2L

private const val DEFAULT_PAGE = 100
private const val MAX_PAGE = 1_000

/** Time for a request that has just timed out to finish its coroutine. */
private val STOP_GRACE_MARGIN: Duration = Duration.ofSeconds(1)

/** Error texts of the generator's API that callers may match on. */
object GeneratorErrors {
    const val RUN_STILL_ACTIVE = "run still active"
    const val PERIOD_NOT_CLOSED = "period not closed"
}

open class ConflictException(message: String) : RuntimeException(message)

class SimulationAlreadyRunningException(runId: UUID) : ConflictException("Simulation $runId is already running")

class JournalCapacityException(message: String) : ConflictException(message)

class RunStillActiveException : ConflictException(GeneratorErrors.RUN_STILL_ACTIVE)

class NotFoundException(message: String) : RuntimeException(message)

@Schema(description = "Body of an error answer.")
data class ApiError(@field:Schema(description = "Names the case.") val error: String)

@RestController
@RequestMapping("/internal/simulations")
class SimulationController(
    private val client: GatewayClient,
    private val props: GeneratorProperties,
    private val registry: MeterRegistry,
) {
    private val simulations = ConcurrentHashMap<UUID, Simulation>()

    @ApiResponse(responseCode = "201", description = "Started")
    @ApiResponse(responseCode = "400", description = "Invalid request, or the run alone exceeds the journal cap", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "409", description = "Already running, or journals in memory leave no room", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@Valid @RequestBody request: StartSimulationRequest): SimulationStatus {
        val runId = request.runId!!
        val duration = Duration.ofSeconds(request.durationSeconds.toLong())
        val capacity = Simulation.journalCapacity(request.tps, duration)
        require(capacity <= props.journal.maxEntries) {
            "tps x durationSeconds = $capacity exceeds the journal cap of ${props.journal.maxEntries}"
        }
        // The capacity check and the insert must not interleave with another start.
        synchronized(simulations) {
            val existing = simulations[runId]
            if (existing?.state?.active == true) throw SimulationAlreadyRunningException(runId)
            val reservedByOtherRuns = simulations.filterKeys { it != runId }.values.filter { it.journal != null }.sumOf { it.journalCapacity }
            if (reservedByOtherRuns + capacity > props.journal.maxEntries) {
                throw JournalCapacityException(
                    "journals in memory hold $reservedByOtherRuns of ${props.journal.maxEntries} entries; retry after older journals are released",
                )
            }
            val profile = LoadProfile(
                runId, request.accountCount, request.blockedAccounts, request.hotAccounts,
                oversizedShare = props.profile.oversizedShare, blockedShare = props.profile.blockedShare,
            )
            val simulation = Simulation(
                runId, request.tps, duration, profile,
                request.waitShare, Duration.ofSeconds(request.waitTimeoutSeconds.toLong()),
                stopGrace(request.waitTimeoutSeconds), client, props.maxInFlight, registry,
                props.profile.duplicateShare, props.profile.duplicateDelays,
            )
            simulations[runId] = simulation.also { it.start() }
            return simulation.status()
        }
    }

    /** Finished simulations stay readable for a while, then go, so a long-lived pod does not grow forever. */
    @Scheduled(fixedDelayString = "\${generator.retention-sweep-interval}")
    fun evictFinished() {
        val now = Instant.now()
        val statusesUntil = now.minus(props.retention)
        val journalsUntil = now.minus(props.journal.retention)
        synchronized(simulations) {
            simulations.entries.removeIf { (_, simulation) -> simulation.finishedBefore(statusesUntil) }
            simulations.values
                .filter { it.journal != null && it.finishedBefore(journalsUntil) }
                .forEach { it.releaseJournal() }
        }
    }

    @ApiResponse(responseCode = "200", description = "The simulation's state and counts")
    @ApiResponse(responseCode = "404", description = "Unknown run, or evicted after the retention", content = [Content(schema = Schema(implementation = ApiError::class))])
    @GetMapping("/{runId}")
    fun status(@PathVariable runId: UUID): SimulationStatus = simulationOf(runId).status()

    @GetMapping("/{runId}/transfers/{transferId}")
    fun transfer(@PathVariable runId: UUID, @PathVariable transferId: UUID): JournalEntryView =
        journalOf(simulationOf(runId)).get(transferId)
            ?: throw NotFoundException("No transfer $transferId in the journal of simulation $runId")

    /** Pages in the order of first send; while the run is active [JournalPage.next] is always set, as entries keep coming. */
    @GetMapping("/{runId}/transfers")
    fun transfers(
        @PathVariable runId: UUID,
        @RequestParam outcome: JournalOutcome?,
        @RequestParam resent: Boolean?,
        @RequestParam rejectReason: RejectReason?,
        @RequestParam(defaultValue = "0") after: Long,
        @RequestParam limit: Int?,
    ): JournalPage {
        val pageSize = limit ?: DEFAULT_PAGE
        require(pageSize in 1..MAX_PAGE) { "limit must be between 1 and $MAX_PAGE" }
        require(after >= 0) { "after must not be negative" }
        val simulation = simulationOf(runId)
        // Read before the page: a run that is already over adds no entries, so the page may say there is no next one.
        val complete = !simulation.state.active
        return journalOf(simulation).page(JournalFilter(outcome, resent, rejectReason), after, pageSize, complete)
    }

    @ApiResponse(responseCode = "200", description = "The report")
    @ApiResponse(responseCode = "404", description = "Unknown run, or its journal released", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "409", description = "The run is still active, or the period is not closed", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "502", description = "The gateway's statement is inconsistent or unreadable", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "503", description = "The gateway did not answer", content = [Content(schema = Schema(implementation = ApiError::class))])
    @GetMapping("/{runId}/reconciliation")
    fun reconciliation(@PathVariable runId: UUID): ClientReconciliationReport {
        val simulation = simulationOf(runId)
        if (simulation.state.active) throw RunStillActiveException()
        val reconciliation = ClientReconciliation(runId, journalOf(simulation).entries())
        val cursors = HashSet<UUID>()
        var entryCount: Long? = null
        var after: UUID? = null
        do {
            val page = client.statement(runId, after)
            if (entryCount != null && page.entryCount != entryCount) {
                throw BadGatewayAnswerException("The statement of run $runId changed its totals between pages: entryCount $entryCount, then ${page.entryCount}")
            }
            entryCount = page.entryCount
            for (line in page.items) {
                if (!reconciliation.add(line)) throw BadGatewayAnswerException("The statement of run $runId lists transfer ${line.transferId} twice")
            }
            after = page.next
            if (after != null && !cursors.add(after)) throw BadGatewayAnswerException("The statement of run $runId pages back to $after")
        } while (after != null)
        val report = reconciliation.report()
        if (report.statement.rows != entryCount) {
            throw BadGatewayAnswerException("The statement of run $runId is incomplete: ${report.statement.rows} lines, entryCount $entryCount")
        }
        return report
    }

    private fun simulationOf(runId: UUID): Simulation =
        simulations[runId] ?: throw NotFoundException("No simulation $runId")

    private fun journalOf(simulation: Simulation): Journal =
        simulation.journal ?: throw NotFoundException("The journal of simulation ${simulation.runId} has been released")

    /**
     * Starts a graceful stop. 202 while the run is still stopping: poll the status until it is no
     * longer active. 200 if the run has already ended, on its own or by an earlier stop.
     */
    @ApiResponse(responseCode = "200", description = "Already ended")
    @ApiResponse(responseCode = "202", description = "Stopping; poll the status")
    @ApiResponse(responseCode = "404", description = "Unknown run", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping("/{runId}/stop")
    fun stop(@PathVariable runId: UUID): ResponseEntity<SimulationStatus> {
        val simulation = simulationOf(runId)
        simulation.stop()
        val status = simulation.status()
        return if (status.state.active) ResponseEntity.accepted().body(status) else ResponseEntity.ok(status)
    }

    /**
     * The slowest open request ends by its own timeouts: connecting, then the wait plus the
     * request timeout. No retry starts after a stop, so backoffs only need the margin.
     */
    private fun stopGrace(waitTimeoutSeconds: Int): Duration =
        props.connectTimeout + Duration.ofSeconds(waitTimeoutSeconds.toLong()) + props.requestTimeout + STOP_GRACE_MARGIN

    @ExceptionHandler(ConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun conflict(e: ConflictException) = ApiError(e.message ?: "conflict")

    @ExceptionHandler(GatewayUnavailableException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun gatewayUnavailable(e: GatewayUnavailableException) = ApiError(e.message ?: "gateway unavailable")

    @ExceptionHandler(BadGatewayAnswerException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun gatewayAnswer(e: BadGatewayAnswerException) = ApiError(e.message ?: "bad gateway")

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(e: NotFoundException) = ApiError(e.message ?: "not found")

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(e: IllegalArgumentException) = ApiError(e.message ?: "bad request")
}
