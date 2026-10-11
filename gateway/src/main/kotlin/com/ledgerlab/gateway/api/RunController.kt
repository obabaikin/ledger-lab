package com.ledgerlab.gateway.api

import com.ledgerlab.gateway.domain.RunPeriod
import com.ledgerlab.gateway.domain.RunSummary
import com.ledgerlab.gateway.service.PeriodService
import com.ledgerlab.gateway.service.TransferService
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
import java.util.UUID

/** Service-to-service API used by the control service; not exposed outside the cluster. */
@RestController
@RequestMapping("/internal/runs/{runId}")
class RunController(private val service: TransferService, private val periods: PeriodService) {

    @GetMapping("/summary")
    fun summary(@PathVariable runId: UUID): RunSummary = service.summarize(runId)

    /** Closes the run's period once its client is done (ADR 0009). 503 if inserts in flight outlast the lock timeout; retrying is safe. */
    @PostMapping("/close")
    fun close(@PathVariable runId: UUID): RunPeriod = periods.close(runId)

    @DeleteMapping
    fun delete(@PathVariable runId: UUID): ResponseEntity<Void> =
        if (service.deleteRun(runId)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    @ExceptionHandler(CannotAcquireLockException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun lockTimeout(e: CannotAcquireLockException) = ApiError(GatewayErrors.CLOSE_LOCK_TIMEOUT)
}
