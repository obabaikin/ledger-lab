package com.ledgerlab.gateway.api

import com.ledgerlab.common.Observability.MDC_RUN_ID
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.gateway.GatewayProperties
import com.ledgerlab.gateway.domain.Transfer
import com.ledgerlab.gateway.domain.TransferStatus
import com.ledgerlab.gateway.service.IdempotencyConflictException
import com.ledgerlab.gateway.service.PeriodClosedException
import com.ledgerlab.gateway.service.ResultWaiters
import com.ledgerlab.gateway.service.TransferRequest
import com.ledgerlab.gateway.service.TransferService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class SubmitTransferRequest(
    @field:NotNull val transferId: UUID?,
    @field:NotNull val runId: UUID?,
    @field:Min(1) val fromAccount: Int,
    @field:Min(1) val toAccount: Int,
    @field:Min(1) val amountCents: Long,
)

data class TransferResponse(
    val transferId: UUID,
    val runId: UUID,
    val status: TransferStatus,
    val rejectReason: String?,
    val acceptedAt: Instant,
    val resultedAt: Instant?,
) {
    companion object {
        fun of(t: Transfer) = TransferResponse(t.transferId, t.runId, t.status, t.rejectReason, t.acceptedAt, t.resultedAt)
    }
}

/** Public API of the bank: this is what clients (and the load generator) call. */
@RestController
@RequestMapping("/{version}/transfers")
class TransferController(
    private val service: TransferService,
    private val waiters: ResultWaiters,
    private val props: GatewayProperties,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val waitFailures = Counter.builder("gateway.wait.failed")
        .description("accepted transfers whose result could not be read while the client waited")
        .register(registry)

    /**
     * Returns 202 as soon as the transfer is durably accepted. With `wait`, the request stays
     * open until the ledger's result arrives (200) or the wait expires (202 with current state).
     * Once the transfer is committed the answer is 2xx, never 5xx (client contract, ADR 0006).
     */
    @PostMapping(version = "1")
    fun submit(
        @Valid @RequestBody request: SubmitTransferRequest,
        @RequestParam(required = false) wait: Duration?,
    ): ResponseEntity<TransferResponse> {
        val transferId = request.transferId!!
        MDC.put(MDC_RUN_ID, request.runId.toString())
        val future = wait?.let { waiters.register(transferId) }
        try {
            val transfer = service.accept(
                TransferRequest(transferId, request.runId!!, request.fromAccount, request.toAccount, request.amountCents),
            )
            if (transfer.status.isFinal || future == null) return respond(transfer)

            return try {
                respond(awaitResult(transfer, future, minOf(wait, props.maxWait)))
            } catch (e: Exception) {
                // A 5xx would read as "unknown" although the transfer is committed; it is accepted, final or not.
                waitFailures.increment()
                log.warn("Result of accepted transfer {} could not be read while the client waited", transferId, e)
                respond(transfer)
            }
        } finally {
            future?.let { waiters.remove(transferId, it) }
            MDC.remove(MDC_RUN_ID)
        }
    }

    private fun awaitResult(transfer: Transfer, future: CompletableFuture<TransferResultEvent>, timeout: Duration): Transfer =
        try {
            service.recordResult(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)) ?: transfer
        } catch (e: TimeoutException) {
            service.find(transfer.transferId) ?: transfer
        }

    @GetMapping("/{transferId}", version = "1")
    fun status(@PathVariable transferId: UUID): ResponseEntity<TransferResponse> =
        service.find(transferId)?.let { respond(it) } ?: ResponseEntity.notFound().build()

    private fun respond(transfer: Transfer): ResponseEntity<TransferResponse> =
        if (transfer.status.isFinal) {
            ResponseEntity.ok(TransferResponse.of(transfer))
        } else {
            ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.RETRY_AFTER, props.retryAfter.toSeconds().coerceAtLeast(1).toString())
                .body(TransferResponse.of(transfer))
        }

    @ExceptionHandler(IdempotencyConflictException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    fun idempotencyConflict(e: IdempotencyConflictException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))

    @ExceptionHandler(PeriodClosedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun periodClosed(e: PeriodClosedException): Map<String, String> = mapOf("error" to "period closed")
}
