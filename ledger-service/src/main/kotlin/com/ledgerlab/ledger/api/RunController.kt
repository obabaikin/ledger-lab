package com.ledgerlab.ledger.api

import com.ledgerlab.ledger.domain.Account
import com.ledgerlab.ledger.domain.ReconciliationReport
import com.ledgerlab.ledger.persistence.ReconciliationProperties
import com.ledgerlab.ledger.service.AccountSetup
import com.ledgerlab.ledger.service.RunAlreadyExistsException
import com.ledgerlab.ledger.service.RunService
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.PositiveOrZero
import org.slf4j.LoggerFactory
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class OpenAccountsRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:Min(AccountSetup.MIN_ACCOUNTS) @field:Max(AccountSetup.MAX_ACCOUNTS) val accountCount: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:PositiveOrZero val openingBalanceCents: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:PositiveOrZero val blockedAccounts: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:PositiveOrZero val dailyLimitCents: Long,
)

@Schema(description = "Body of an error answer.")
data class ApiError(@field:Schema(description = "Names the case.") val error: String)

class ReconciliationTimeoutException(val runId: UUID, cause: Throwable) : RuntimeException(cause)

@RestController
@RequestMapping("/internal/runs/{runId}")
class RunController(private val runs: RunService, private val reconciliation: ReconciliationProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApiResponse(responseCode = "201", description = "Accounts opened")
    @ApiResponse(responseCode = "400", description = "Invalid setup", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "409", description = "The run already has accounts", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun openAccounts(@PathVariable runId: UUID, @Valid @RequestBody request: OpenAccountsRequest): List<Account> =
        runs.openAccounts(
            runId,
            AccountSetup(
                accountCount = request.accountCount,
                openingBalanceCents = request.openingBalanceCents,
                blockedAccounts = request.blockedAccounts,
                dailyLimitCents = request.dailyLimitCents,
            ),
        )

    @ApiResponse(responseCode = "200", description = "The run's accounts")
    @GetMapping("/accounts")
    fun accounts(@PathVariable runId: UUID): List<Account> = runs.listAccounts(runId)

    @ApiResponse(responseCode = "200", description = "The ledger's checks; an unknown run has no accounts and zeros")
    @ApiResponse(
        responseCode = "503",
        description = "The database cancelled a reconciliation query at the statement timeout.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @GetMapping("/reconciliation")
    fun reconciliation(@PathVariable runId: UUID): ReconciliationReport =
        try {
            runs.reconcile(runId)
        } catch (e: QueryTimeoutException) {
            throw ReconciliationTimeoutException(runId, e)
        }

    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Unknown run")
    @DeleteMapping
    fun delete(@PathVariable runId: UUID): ResponseEntity<Void> =
        if (runs.deleteRun(runId)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    @ExceptionHandler(RunAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun runExists(e: RunAlreadyExistsException) = ApiError(e.message ?: "conflict")

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidSetup(e: IllegalArgumentException) = ApiError(e.message ?: "bad request")

    @ExceptionHandler(ReconciliationTimeoutException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun reconciliationTimeout(e: ReconciliationTimeoutException): ApiError {
        log.warn("Reconciliation of run {} hit the database statement timeout ({})", e.runId, reconciliation.statementTimeout)
        return ApiError(STATEMENT_TIMEOUT)
    }

    companion object {
        const val STATEMENT_TIMEOUT = "database statement timeout"
    }
}
