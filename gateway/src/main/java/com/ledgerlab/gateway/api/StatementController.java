package com.ledgerlab.gateway.api;

import com.ledgerlab.gateway.domain.Statement;
import com.ledgerlab.gateway.domain.StatementCursor;
import com.ledgerlab.gateway.service.ForeignCursorException;
import com.ledgerlab.gateway.service.PeriodNotClosedException;
import com.ledgerlab.gateway.service.PeriodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/{version}/periods/{periodStart}/statement")
public class StatementController {

    private static final int DEFAULT_PAGE = 100;
    private static final int MAX_PAGE = 1000;

    private static final Logger log = LoggerFactory.getLogger(StatementController.class);

    private final PeriodService periods;

    public StatementController(PeriodService periods) {
        this.periods = periods;
    }

    @Operation(summary = "A page of a closed period's statement: the transfers accepted in it, in the order accepted")
    @ApiResponse(responseCode = "200", description = "The page")
    @ApiResponse(responseCode = "400", description = "Invalid limit, or `after` is not the `next` of a page of this period", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Not a period, not begun yet, or older than the bank keeps transfers", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "The period is not closed yet", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping(version = ApiVersioning.PATH_VERSION)
    public Statement statement(
        @Parameter(description = "Start of the period, an ISO-8601 instant on the bank's grid of periods")
        @PathVariable Instant periodStart,
        @Parameter(description = "The `next` of the previous page; omit for the first page")
        @RequestParam(required = false) @Nullable String after,
        @Parameter(description = "Page size, 1 to " + MAX_PAGE + ", default " + DEFAULT_PAGE)
        @RequestParam(required = false) @Nullable Integer limit
    ) {
        var pageSize = limit != null ? limit : DEFAULT_PAGE;
        if (pageSize < 1 || pageSize > MAX_PAGE) {
            throw new InvalidStatementQueryException(GatewayErrors.INVALID_LIMIT, "limit must be between 1 and " + MAX_PAGE + ", got " + pageSize);
        }
        return periods.statement(periodStart, after == null ? null : cursorOf(after), pageSize);
    }

    private static StatementCursor cursorOf(String after) {
        try {
            return StatementCursor.parse(after);
        } catch (IllegalArgumentException e) {
            throw new InvalidStatementQueryException(GatewayErrors.INVALID_CURSOR, String.valueOf(e.getMessage()));
        }
    }

    @ExceptionHandler(PeriodNotClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError notClosed() {
        return new ApiError(GatewayErrors.PERIOD_NOT_CLOSED);
    }

    @ExceptionHandler(ForeignCursorException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError foreignCursor(ForeignCursorException e) {
        log.info("Statement query refused: {}", e.getMessage());
        return new ApiError(GatewayErrors.INVALID_CURSOR);
    }

    @ExceptionHandler(InvalidStatementQueryException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalid(InvalidStatementQueryException e) {
        log.info("Statement query refused: {}", e.getMessage());
        return new ApiError(e.code());
    }
}
