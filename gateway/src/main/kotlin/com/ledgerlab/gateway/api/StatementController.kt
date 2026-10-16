package com.ledgerlab.gateway.api

import com.ledgerlab.gateway.domain.Statement
import com.ledgerlab.gateway.service.PeriodNotClosedException
import com.ledgerlab.gateway.service.PeriodService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/{version}/runs/{runId}/statement")
class StatementController(private val periods: PeriodService) {

    @Operation(summary = "A page of the closed period's statement")
    @ApiResponse(responseCode = "200", description = "The page")
    @ApiResponse(responseCode = "400", description = "Invalid limit, or `after` is not a transfer of the run", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "409", description = "The period is not closed yet", content = [Content(schema = Schema(implementation = ApiError::class))])
    @GetMapping(version = "v1")
    fun statement(
        @PathVariable runId: UUID,
        @Parameter(description = "The `next` of the previous page; omit for the first page")
        @RequestParam after: UUID?,
        @Parameter(description = "Page size, 1 to 1000, default 100")
        @RequestParam limit: Int?,
    ): Statement {
        val pageSize = limit ?: DEFAULT_PAGE
        require(pageSize in 1..MAX_PAGE) { "limit must be between 1 and $MAX_PAGE" }
        return periods.statement(runId, after, pageSize)
    }

    @ExceptionHandler(PeriodNotClosedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun notClosed() = ApiError(GatewayErrors.PERIOD_NOT_CLOSED)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(e: IllegalArgumentException) = ApiError(e.message ?: GatewayErrors.BAD_REQUEST)

    private companion object {
        const val DEFAULT_PAGE = 100
        const val MAX_PAGE = 1000
    }
}
