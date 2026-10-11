package com.ledgerlab.gateway.api

import com.ledgerlab.gateway.domain.RunSummary
import com.ledgerlab.gateway.service.PeriodService
import com.ledgerlab.gateway.service.TransferService
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/internal/runs/{runId}")
class RunController(private val service: TransferService, private val periods: PeriodService) {

    @ApiResponse(responseCode = "200", description = "The run's counts; an unknown run has zeros")
    @GetMapping("/summary")
    fun summary(@PathVariable runId: UUID): RunSummary = service.summarize(runId)

    @ApiResponse(responseCode = "200", description = "Closed; a repeat returns the same closedAt")
    @ApiResponse(responseCode = "503", description = "Transfers of the run kept the lock past the timeout", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping("/close")
    fun close(@PathVariable runId: UUID): ClosedPeriod = periods.close(runId).let { ClosedPeriod(it.runId, it.closedAt) }

    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Unknown run")
    @DeleteMapping
    fun delete(@PathVariable runId: UUID): ResponseEntity<Void> =
        if (service.deleteRun(runId)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    @ExceptionHandler(CannotAcquireLockException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun lockTimeout(e: CannotAcquireLockException) = ApiError(GatewayErrors.CLOSE_LOCK_TIMEOUT)
}

data class ClosedPeriod(val runId: UUID, val closedAt: Instant)
