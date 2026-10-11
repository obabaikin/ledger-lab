package com.ledgerlab.ledger.api

import com.ledgerlab.ledger.domain.Account
import com.ledgerlab.ledger.domain.ReconciliationReport
import com.ledgerlab.ledger.service.AccountSetup
import com.ledgerlab.ledger.service.RunAlreadyExistsException
import com.ledgerlab.ledger.service.RunService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.PositiveOrZero
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
    @field:Min(AccountSetup.MIN_ACCOUNTS) @field:Max(AccountSetup.MAX_ACCOUNTS) val accountCount: Int,
    @field:PositiveOrZero val openingBalanceCents: Long,
    @field:PositiveOrZero val blockedAccounts: Int,
    @field:PositiveOrZero val dailyLimitCents: Long,
)

@RestController
@RequestMapping("/internal/runs/{runId}")
class RunController(private val runs: RunService) {

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

    @GetMapping("/accounts")
    fun accounts(@PathVariable runId: UUID): List<Account> = runs.listAccounts(runId)

    @GetMapping("/reconciliation")
    fun reconciliation(@PathVariable runId: UUID): ReconciliationReport = runs.reconcile(runId)

    @DeleteMapping
    fun delete(@PathVariable runId: UUID): ResponseEntity<Void> =
        if (runs.deleteRun(runId)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    @ExceptionHandler(RunAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun runExists(e: RunAlreadyExistsException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidSetup(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "bad request"))
}
