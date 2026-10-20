package com.ledgerlab.gateway.api

import com.ledgerlab.common.Observability.MDC_RUN_ID
import com.ledgerlab.common.RejectReason
import com.ledgerlab.gateway.GatewayProperties
import com.ledgerlab.gateway.domain.REJECT_REASON_DESCRIPTION
import com.ledgerlab.gateway.domain.Transfer
import com.ledgerlab.gateway.domain.TransferStatus
import com.ledgerlab.gateway.service.GatewayBusyException
import com.ledgerlab.gateway.service.IdempotencyConflictException
import com.ledgerlab.gateway.service.LedgerResult
import com.ledgerlab.gateway.service.PeriodClosedException
import com.ledgerlab.gateway.service.ResultWaiters
import com.ledgerlab.gateway.service.TransferAdmission
import com.ledgerlab.gateway.service.TransferRequest
import com.ledgerlab.gateway.service.TransferService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
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

@Schema(description = "A transfer to submit.")
data class SubmitTransferRequest(
    @field:Schema(description = "Idempotency key: a repeat with the same body gets the same transfer.")
    @field:NotNull val transferId: UUID?,
    @field:NotNull val runId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) @field:Min(1) val fromAccount: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) @field:Min(1) val toAccount: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) @field:Min(1) val amountCents: Long,
)

@Schema(description = "A transfer as the bank sees it now.")
data class TransferResponse(
    val transferId: UUID,
    val runId: UUID,
    val status: TransferStatus,
    @field:Schema(description = REJECT_REASON_DESCRIPTION)
    val rejectReason: RejectReason?,
    val acceptedAt: Instant,
    @field:Schema(description = "When the final status was recorded; null while not final.")
    val resultedAt: Instant?,
)

@RestController
@RequestMapping("/{version}/transfers")
class TransferController(
    private val service: TransferService,
    private val waiters: ResultWaiters,
    private val admission: TransferAdmission,
    private val props: GatewayProperties,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val waitFailures = Counter.builder("gateway.wait.failed")
        .description("accepted transfers whose result could not be read while the client waited")
        .register(registry)
    private val retryAfterSeconds = props.retryAfter.toSeconds().coerceAtLeast(1).toString()

    @Operation(summary = "Submit a transfer")
    @ApiResponse(responseCode = "200", description = "Final status")
    @ApiResponse(responseCode = "202", description = "Accepted, not final yet; Retry-After says when to ask again")
    @ApiResponse(responseCode = "400", description = "Invalid body", content = [Content()])
    @ApiResponse(responseCode = "409", description = "The run's period is closed", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "422", description = "The key was used with a different body", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(
        responseCode = "503",
        description = "With error \"${GatewayErrors.BUSY}\": too many transfers in progress, this one was not taken; retry with the same key after Retry-After. Any other 5xx leaves the transfer unknown.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @PostMapping(version = ApiVersioning.PATH_VERSION)
    fun submit(
        @Valid @RequestBody request: SubmitTransferRequest,
        @Parameter(description = "ISO-8601 duration to wait for the final status, capped by the bank")
        @RequestParam(required = false) wait: Duration?,
    ): ResponseEntity<TransferResponse> {
        val transferId = request.transferId!!
        MDC.put(MDC_RUN_ID, request.runId.toString())
        val future = wait?.let { waiters.register(transferId) }
        try {
            val transfer = admission.admit {
                service.accept(
                    TransferRequest(transferId, request.runId!!, request.fromAccount, request.toAccount, request.amountCents),
                )
            }
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

    private fun awaitResult(transfer: Transfer, future: CompletableFuture<LedgerResult>, timeout: Duration): Transfer =
        try {
            service.recordResult(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)) ?: transfer
        } catch (e: TimeoutException) {
            service.find(transfer.transferId) ?: transfer
        }

    @Operation(summary = "A transfer's current status")
    @ApiResponse(responseCode = "200", description = "Final status")
    @ApiResponse(responseCode = "202", description = "Not final yet; Retry-After says when to ask again")
    @ApiResponse(responseCode = "404", description = "Unknown transfer", content = [Content()])
    @GetMapping("/{transferId}", version = ApiVersioning.PATH_VERSION)
    fun status(@PathVariable transferId: UUID): ResponseEntity<TransferResponse> =
        service.find(transferId)?.let { respond(it) } ?: ResponseEntity.notFound().build()

    private fun respond(transfer: Transfer): ResponseEntity<TransferResponse> =
        if (transfer.status.isFinal) {
            ResponseEntity.ok(transfer.toResponse())
        } else {
            ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
                .body(transfer.toResponse())
        }

    @ExceptionHandler(IdempotencyConflictException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    fun idempotencyConflict(e: IdempotencyConflictException) = ApiError(e.message ?: GatewayErrors.CONFLICT)

    @ExceptionHandler(GatewayBusyException::class)
    fun busy(): ResponseEntity<ApiError> = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
        .body(ApiError(GatewayErrors.BUSY))

    @ExceptionHandler(PeriodClosedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun periodClosed() = ApiError(GatewayErrors.PERIOD_CLOSED)
}

internal fun Transfer.toResponse() = TransferResponse(transferId, runId, status, rejectReason, acceptedAt, resultedAt)
