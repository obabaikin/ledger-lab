package com.ledgerlab.generator

import io.micrometer.core.instrument.MeterRegistry
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StartSimulationRequest(
    @field:NotNull val runId: UUID?,
    @field:Min(1) @field:Max(5_000) val tps: Int,
    @field:Min(1) @field:Max(300) val durationSeconds: Int,
    @field:Min(2) val accountCount: Int,
    @field:Min(0) val blockedAccounts: Int = 0,
    @field:Min(0) val hotAccounts: Int = 5,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val waitShare: Double = 0.1,
    @field:Min(1) @field:Max(30) val waitTimeoutSeconds: Int = 5,
)

/** Time for a request that has just timed out to finish its coroutine. */
private val STOP_GRACE_MARGIN: Duration = Duration.ofSeconds(1)

class SimulationAlreadyRunningException(runId: UUID) : RuntimeException("Simulation $runId is already running")

/** Service-to-service API used by the control service to drive a run. */
@RestController
@RequestMapping("/internal/simulations")
class SimulationController(
    private val client: GatewayClient,
    private val props: GeneratorProperties,
    private val registry: MeterRegistry,
) {
    private val simulations = ConcurrentHashMap<UUID, Simulation>()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@Valid @RequestBody request: StartSimulationRequest): SimulationStatus {
        val runId = request.runId!!
        val profile = LoadProfile(runId, request.accountCount, request.blockedAccounts, request.hotAccounts)
        val simulation = Simulation(
            runId, request.tps, Duration.ofSeconds(request.durationSeconds.toLong()), profile,
            request.waitShare, Duration.ofSeconds(request.waitTimeoutSeconds.toLong()),
            stopGrace(request.waitTimeoutSeconds), client, props.maxInFlight, registry,
        )
        // compute() holds the map entry while the check and the start happen, so two concurrent
        // starts for the same run cannot both pass.
        simulations.compute(runId) { _, existing ->
            if (existing?.state?.active == true) throw SimulationAlreadyRunningException(runId)
            simulation.also { it.start() }
        }
        return simulation.status()
    }

    /** Finished simulations stay readable for a while, then go, so a long-lived pod does not grow forever. */
    @Scheduled(fixedDelayString = "\${generator.retention-sweep-interval}")
    fun evictFinished() {
        val cutoff = Instant.now().minus(props.retention)
        simulations.entries.removeIf { (_, simulation) ->
            !simulation.state.active && (simulation.finishedAt ?: Instant.MAX).isBefore(cutoff)
        }
    }

    @GetMapping("/{runId}")
    fun status(@PathVariable runId: UUID): ResponseEntity<SimulationStatus> =
        simulations[runId]?.let { ResponseEntity.ok(it.status()) } ?: ResponseEntity.notFound().build()

    /**
     * Starts a graceful stop. 202 while the run is still stopping: poll the status until it is no
     * longer active. 200 if the run has already ended, on its own or by an earlier stop.
     */
    @PostMapping("/{runId}/stop")
    fun stop(@PathVariable runId: UUID): ResponseEntity<SimulationStatus> {
        val simulation = simulations[runId] ?: return ResponseEntity.notFound().build()
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

    @ExceptionHandler(SimulationAlreadyRunningException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun alreadyRunning(e: SimulationAlreadyRunningException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "bad request"))
}
