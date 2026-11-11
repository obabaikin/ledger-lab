package com.ledgerlab.reporting.api;

import com.ledgerlab.reporting.domain.PeriodStats;
import com.ledgerlab.reporting.service.PeriodStatsService;
import com.ledgerlab.reporting.service.UnknownPeriodException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/periods")
public class PeriodStatsController {

    private static final Logger log = LoggerFactory.getLogger(PeriodStatsController.class);

    private final PeriodStatsService stats;

    public PeriodStatsController(PeriodStatsService stats) {
        this.stats = stats;
    }

    @Operation(summary = "The current period's numbers from the bank's events")
    @ApiResponse(responseCode = "200", description = "The snapshot; a period with no events yet has zeros")
    @GetMapping("/current/stats")
    public PeriodStats current() {
        return stats.current();
    }

    @Operation(summary = "A period's numbers from the bank's events")
    @ApiResponse(responseCode = "200", description = "The snapshot; a period with no events has zeros")
    @ApiResponse(responseCode = "404", description = "Not a period, not begun yet, or older than reporting keeps", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{periodStart}/stats")
    public PeriodStats stats(@PathVariable Instant periodStart) {
        return stats.of(periodStart);
    }

    @ExceptionHandler(UnknownPeriodException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError unknownPeriod(UnknownPeriodException e) {
        log.info("{}", e.getMessage());
        return new ApiError(ReportingErrors.UNKNOWN_PERIOD);
    }
}
