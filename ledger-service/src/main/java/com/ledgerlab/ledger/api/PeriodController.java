package com.ledgerlab.ledger.api;

import com.ledgerlab.ledger.domain.Books;
import com.ledgerlab.ledger.domain.PeriodReconciliation;
import com.ledgerlab.ledger.persistence.ReconciliationProperties;
import com.ledgerlab.ledger.service.PeriodCheckStarter;
import com.ledgerlab.ledger.service.PeriodService;
import com.ledgerlab.ledger.service.UnknownPeriodException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class PeriodController {

    public static final String STATEMENT_TIMEOUT = "database statement timeout";
    public static final String UNKNOWN_PERIOD = "unknown period";

    private static final Logger log = LoggerFactory.getLogger(PeriodController.class);

    private final PeriodService periods;
    private final PeriodCheckStarter checks;
    private final ReconciliationProperties reconciliation;

    public PeriodController(PeriodService periods, PeriodCheckStarter checks, ReconciliationProperties reconciliation) {
        this.periods = periods;
        this.checks = checks;
        this.reconciliation = reconciliation;
    }

    @Operation(summary = "Transfers of an ended period by outcome, and its check once made")
    @ApiResponse(responseCode = "200", description = "Counted now; the check is absent until it has finished")
    @ApiResponse(responseCode = "404", description = "Not a period, not ended yet, or older than the ledger keeps", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "The database cancelled a query at the statement timeout", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/periods/{periodStart}/reconciliation")
    public PeriodReconciliation reconciliation(@PathVariable Instant periodStart) {
        return periods.reconciliation(periodStart);
    }

    @Operation(summary = "Start the period's check in the background; call once all its transfers are booked")
    @ApiResponse(responseCode = "202", description = "Started, running or already made; the reconciliation shows it when finished")
    @ApiResponse(responseCode = "404", description = "Not a period, not ended yet, or older than the ledger keeps", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/periods/{periodStart}/check")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void check(@PathVariable Instant periodStart) {
        checks.start(periods.endedPeriod(periodStart));
    }

    @Operation(summary = "All balances against all opening balances")
    @ApiResponse(responseCode = "200", description = "The sums as of now")
    @ApiResponse(responseCode = "503", description = "The database cancelled the query at the statement timeout", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/books")
    public Books books() {
        return periods.books();
    }

    @ExceptionHandler(UnknownPeriodException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError unknownPeriod(UnknownPeriodException e) {
        return new ApiError(UNKNOWN_PERIOD);
    }

    @ExceptionHandler(QueryTimeoutException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError statementTimeout(QueryTimeoutException e) {
        log.warn("A reconciliation query hit the database statement timeout ({} ms)", reconciliation.statementTimeout().toMillis(), e);
        return new ApiError(STATEMENT_TIMEOUT);
    }
}
