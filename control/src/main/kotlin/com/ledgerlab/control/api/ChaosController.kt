package com.ledgerlab.control.api

import com.ledgerlab.control.entry.EntryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Phase 6 deletes the ledger pod here; until then the place on the page exists and says so. */
const val CHAOS_AVAILABLE = false

class ChaosNotAvailableException : RuntimeException()

@RestController
class ChaosController(private val entry: EntryService) {

    @Operation(summary = "Delete the ledger pod during a run")
    @ApiResponse(responseCode = "404", description = "Unknown session", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "501", description = "Not available in this deployment", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping(CHAOS_PATH)
    fun chaos(@PathVariable sessionId: UUID) {
        entry.overview(sessionId)
        throw ChaosNotAvailableException()
    }

    companion object {
        const val CHAOS_PATH = "/api/sessions/{sessionId}/chaos"
    }
}
