package com.ledgerlab.control.api

import com.ledgerlab.control.chaos.ChaosService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@Schema(description = "The ledger instances that were crashed.")
data class ChaosView(
    @field:Schema(description = "When the instances were crashed, by control's clock.")
    val at: Instant,
    @field:Schema(description = "Instances that took the crash; those that did not answer are left out.")
    val instances: List<String>,
)

@RestController
class ChaosController(private val chaos: ChaosService) {

    @Operation(summary = "Crash every ledger instance during a run")
    @ApiResponse(responseCode = "202", description = "Crashed; the instances restart and the run goes on")
    @ApiResponse(responseCode = "404", description = "Unknown session", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "409", description = "The session has no running run", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(
        responseCode = "429", description = "Chaos was used on the cluster recently (Retry-After says when)",
        headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds until chaos is available again", schema = Schema(type = "integer"))],
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @ApiResponse(responseCode = "501", description = "Not available in this deployment", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "503", description = "No ledger instance answered", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping(CHAOS_PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun chaos(@PathVariable sessionId: UUID): ChaosView = chaos.crash(sessionId).let { ChaosView(it.at, it.instances) }

    companion object {
        const val CHAOS_PATH = "/api/sessions/{sessionId}/chaos"
    }
}
