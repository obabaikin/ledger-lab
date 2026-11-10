package com.ledgerlab.gateway.api;

import com.ledgerlab.gateway.domain.RunSummary;
import com.ledgerlab.gateway.service.PeriodService;
import com.ledgerlab.gateway.service.TransferService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/runs/{runId}")
public class RunController {

    private final TransferService service;
    private final PeriodService periods;

    public RunController(TransferService service, PeriodService periods) {
        this.service = service;
        this.periods = periods;
    }

    @ApiResponse(responseCode = "200", description = "The run's counts; an unknown run has zeros")
    @GetMapping("/summary")
    public RunSummary summary(@PathVariable UUID runId) {
        return service.summarize(runId);
    }

    @ApiResponse(responseCode = "200", description = "Closed; a repeat returns the same closedAt")
    @ApiResponse(responseCode = "503", description = "Transfers of the run kept the lock past the timeout", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/close")
    public ClosedPeriod close(@PathVariable UUID runId) {
        var period = periods.close(runId);
        return new ClosedPeriod(period.runId(), period.closedAt());
    }

    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Unknown run")
    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable UUID runId) {
        return service.deleteRun(runId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(CannotAcquireLockException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError lockTimeout() {
        return new ApiError(GatewayErrors.CLOSE_LOCK_TIMEOUT);
    }
}
