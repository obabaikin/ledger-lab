package com.ledgerlab.gateway.api;

import com.ledgerlab.gateway.domain.ClosedPeriod;
import com.ledgerlab.gateway.domain.PeriodSummary;
import com.ledgerlab.gateway.service.PeriodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/periods/{periodStart}")
public class PeriodController {

    private static final Logger log = LoggerFactory.getLogger(PeriodController.class);

    private final PeriodService periods;

    public PeriodController(PeriodService periods) {
        this.periods = periods;
    }

    @Operation(summary = "Transfers accepted in the period, by status")
    @ApiResponse(responseCode = "200", description = "Counted now")
    @ApiResponse(responseCode = "404", description = "Not a period, not begun yet, or older than the gateway keeps", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/summary")
    public PeriodSummary summary(@PathVariable Instant periodStart) {
        return periods.summarize(periodStart);
    }

    @Operation(summary = "Close an ended period: fix the set of its transfers")
    @ApiResponse(responseCode = "200", description = "Closed; a repeat returns the same answer")
    @ApiResponse(responseCode = "404", description = "Not a period, not ended yet, or older than the gateway keeps", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Inserts in flight kept the lock past the timeout; ask again", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/close")
    public ClosedPeriod close(@PathVariable Instant periodStart) {
        return periods.close(periodStart);
    }

    @ExceptionHandler(CannotAcquireLockException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError lockTimeout(CannotAcquireLockException e) {
        log.warn("A period was not closed", e);
        return new ApiError(GatewayErrors.CLOSE_LOCK_TIMEOUT);
    }
}
