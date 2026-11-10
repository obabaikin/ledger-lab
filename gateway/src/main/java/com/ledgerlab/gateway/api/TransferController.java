package com.ledgerlab.gateway.api;

import static com.ledgerlab.common.Observability.MDC_RUN_ID;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.service.GatewayBusyException;
import com.ledgerlab.gateway.service.IdempotencyConflictException;
import com.ledgerlab.gateway.service.LedgerResult;
import com.ledgerlab.gateway.service.PeriodClosedException;
import com.ledgerlab.gateway.service.RecordedResultReader;
import com.ledgerlab.gateway.service.ResultWaiters;
import com.ledgerlab.gateway.service.TransferAdmission;
import com.ledgerlab.gateway.service.TransferBatcher;
import com.ledgerlab.gateway.service.TransferRequest;
import com.ledgerlab.gateway.service.TransferService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/{version}/transfers")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService service;
    private final TransferBatcher batcher;
    private final ResultWaiters waiters;
    private final RecordedResultReader results;
    private final TransferAdmission admission;
    private final GatewayProperties props;
    private final Counter waitFailures;
    private final String retryAfterSeconds;

    public TransferController(
        TransferService service,
        TransferBatcher batcher,
        ResultWaiters waiters,
        RecordedResultReader results,
        TransferAdmission admission,
        GatewayProperties props,
        MeterRegistry registry
    ) {
        this.service = service;
        this.batcher = batcher;
        this.waiters = waiters;
        this.results = results;
        this.admission = admission;
        this.props = props;
        this.waitFailures = Counter.builder("gateway.wait.failed")
            .description("accepted transfers whose result could not be read while the client waited")
            .register(registry);
        this.retryAfterSeconds = Long.toString(Math.max(props.retryAfter().toSeconds(), 1));
    }

    @Operation(summary = "Submit a transfer")
    @ApiResponse(responseCode = "200", description = "Final status")
    @ApiResponse(responseCode = "202", description = "Accepted, not final yet; Retry-After says when to ask again")
    @ApiResponse(responseCode = "400", description = "Invalid body", content = @Content)
    @ApiResponse(responseCode = "409", description = "The run's period is closed", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "The key was used with a different body", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
        responseCode = "503",
        description = "With error \"" + GatewayErrors.BUSY + "\": too many transfers in progress, this one was not taken; retry with the same key after Retry-After. Any other 5xx leaves the transfer unknown.",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(version = ApiVersioning.PATH_VERSION)
    public ResponseEntity<TransferResponse> submit(
        @Valid @RequestBody SubmitTransferRequest request,
        @Parameter(description = "ISO-8601 duration to wait for the final status, capped by the bank")
        @RequestParam(required = false) @Nullable Duration wait
    ) throws InterruptedException {
        return admission.enter(() -> submitEntered(request, wait));
    }

    private ResponseEntity<TransferResponse> submitEntered(SubmitTransferRequest request, @Nullable Duration wait) throws InterruptedException {
        var transferId = Objects.requireNonNull(request.transferId(), "transferId of a validated request");
        MDC.put(MDC_RUN_ID, String.valueOf(request.runId()));
        var future = wait == null ? null : waiters.register(transferId);
        try {
            var transfer = admission.admit(() -> batcher.accept(new TransferRequest(
                transferId,
                Objects.requireNonNull(request.runId(), "runId of a validated request"),
                request.fromAccount(),
                request.toAccount(),
                request.amountCents())));
            if (transfer.status().isFinal() || future == null || wait == null) {
                return respond(transfer);
            }

            try {
                return respond(awaitResult(transfer, future, wait.compareTo(props.maxWait()) <= 0 ? wait : props.maxWait()));
            } catch (Exception e) {
                // A 5xx would read as "unknown" although the transfer is committed; it is accepted, final or not.
                waitFailures.increment();
                log.warn("Result of accepted transfer {} could not be read while the client waited", transferId, e);
                return respond(transfer);
            }
        } finally {
            if (future != null) {
                waiters.remove(transferId, future);
            }
            MDC.remove(MDC_RUN_ID);
        }
    }

    private Transfer awaitResult(Transfer transfer, CompletableFuture<LedgerResult> future, Duration timeout)
        throws InterruptedException, ExecutionException {
        var deadline = System.nanoTime() + timeout.toNanos();
        try {
            future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            var current = service.find(transfer.transferId());
            return current != null ? current : transfer;
        }
        var recorded = results.readFinal(transfer.transferId(), deadline);
        return recorded != null ? recorded : transfer;
    }

    @Operation(summary = "A transfer's current status")
    @ApiResponse(responseCode = "200", description = "Final status")
    @ApiResponse(responseCode = "202", description = "Not final yet; Retry-After says when to ask again")
    @ApiResponse(responseCode = "404", description = "Unknown transfer", content = @Content)
    @GetMapping(path = "/{transferId}", version = ApiVersioning.PATH_VERSION)
    public ResponseEntity<TransferResponse> status(@PathVariable UUID transferId) {
        var transfer = service.find(transferId);
        return transfer != null ? respond(transfer) : ResponseEntity.notFound().build();
    }

    private ResponseEntity<TransferResponse> respond(Transfer transfer) {
        if (transfer.status().isFinal()) {
            return ResponseEntity.ok(TransferResponse.of(transfer));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
            .body(TransferResponse.of(transfer));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ApiError idempotencyConflict(IdempotencyConflictException e) {
        return new ApiError(e.getMessage() != null ? e.getMessage() : GatewayErrors.CONFLICT);
    }

    @ExceptionHandler(GatewayBusyException.class)
    public ResponseEntity<ApiError> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
            .body(new ApiError(GatewayErrors.BUSY));
    }

    @ExceptionHandler(PeriodClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError periodClosed() {
        return new ApiError(GatewayErrors.PERIOD_CLOSED);
    }
}
