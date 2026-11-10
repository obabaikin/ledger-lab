package com.ledgerlab.gateway.api;

import com.ledgerlab.gateway.domain.Statement;
import com.ledgerlab.gateway.service.PeriodNotClosedException;
import com.ledgerlab.gateway.service.PeriodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/{version}/runs/{runId}/statement")
public class StatementController {

    private static final int DEFAULT_PAGE = 100;
    private static final int MAX_PAGE = 1000;

    private final PeriodService periods;

    public StatementController(PeriodService periods) {
        this.periods = periods;
    }

    @Operation(summary = "A page of the closed period's statement")
    @ApiResponse(responseCode = "200", description = "The page")
    @ApiResponse(responseCode = "400", description = "Invalid limit, or `after` is not a transfer of the run", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "The period is not closed yet", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping(version = ApiVersioning.PATH_VERSION)
    public Statement statement(
        @PathVariable UUID runId,
        @Parameter(description = "The `next` of the previous page; omit for the first page")
        @RequestParam(required = false) @Nullable UUID after,
        @Parameter(description = "Page size, 1 to " + MAX_PAGE + ", default " + DEFAULT_PAGE)
        @RequestParam(required = false) @Nullable Integer limit
    ) {
        var pageSize = limit != null ? limit : DEFAULT_PAGE;
        if (pageSize < 1 || pageSize > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
        }
        return periods.statement(runId, after, pageSize);
    }

    @ExceptionHandler(PeriodNotClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError notClosed() {
        return new ApiError(GatewayErrors.PERIOD_NOT_CLOSED);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalid(IllegalArgumentException e) {
        return new ApiError(e.getMessage() != null ? e.getMessage() : GatewayErrors.BAD_REQUEST);
    }
}
