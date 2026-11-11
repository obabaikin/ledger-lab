package com.ledgerlab.generator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/periods/{periodStart}/reconciliation")
public class ReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationController.class);

    private final PeriodReconciliations reconciliations;

    public ReconciliationController(PeriodReconciliations reconciliations) {
        this.reconciliations = reconciliations;
    }

    @Operation(summary = "Start the client reconciliation of a period the bank has closed")
    @ApiResponse(responseCode = "202", description = "Running: started now, or already running. Poll with GET")
    @ApiResponse(responseCode = "404", description = "Not a period, not ended yet, or older than the journal holds", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PeriodReconciliation start(@PathVariable Instant periodStart) {
        return reconciliations.start(periodStart);
    }

    @Operation(summary = "Where the client reconciliation of a period stands, with its report once done")
    @ApiResponse(responseCode = "200", description = "The state now")
    @ApiResponse(responseCode = "404", description = "Not a period, not ended yet, or older than the journal holds", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    public PeriodReconciliation get(@PathVariable Instant periodStart) {
        return reconciliations.get(periodStart);
    }

    @ExceptionHandler(UnknownPeriodException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError unknownPeriod(UnknownPeriodException e) {
        log.info("{}", e.getMessage());
        return new ApiError(GeneratorErrors.UNKNOWN_PERIOD);
    }
}
