package com.ledgerlab.gateway.api

import com.ledgerlab.gateway.domain.Statement
import com.ledgerlab.gateway.service.PeriodNotClosedException
import com.ledgerlab.gateway.service.PeriodService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Client-facing statement of a run: every transfer the bank accepted in the closed period (ADR 0009). */
@RestController
@RequestMapping("/{version}/runs/{runId}/statement")
class StatementController(private val periods: PeriodService) {

    /** Pages in (acceptedAt, transferId) order; `next` is the `after` of the next page, null on the last one. */
    @GetMapping(version = "1")
    fun statement(
        @PathVariable runId: UUID,
        @RequestParam after: UUID?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): Statement {
        require(limit in 1..MAX_PAGE) { "limit must be between 1 and $MAX_PAGE" }
        return periods.statement(runId, after, limit)
    }

    @ExceptionHandler(PeriodNotClosedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun notClosed(e: PeriodNotClosedException): Map<String, String> = mapOf("error" to "period not closed")

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "bad request"))

    private companion object {
        const val MAX_PAGE = 1000
    }
}
