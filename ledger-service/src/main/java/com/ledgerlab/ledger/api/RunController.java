package com.ledgerlab.ledger.api;

import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.ReconciliationReport;
import com.ledgerlab.ledger.persistence.ReconciliationProperties;
import com.ledgerlab.ledger.service.AccountSetup;
import com.ledgerlab.ledger.service.RunAlreadyExistsException;
import com.ledgerlab.ledger.service.RunService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/runs/{runId}")
public class RunController {

    public static final String STATEMENT_TIMEOUT = "database statement timeout";

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    private final RunService runs;
    private final ReconciliationProperties reconciliation;

    public RunController(RunService runs, ReconciliationProperties reconciliation) {
        this.runs = runs;
        this.reconciliation = reconciliation;
    }

    @ApiResponse(responseCode = "201", description = "Accounts opened")
    @ApiResponse(responseCode = "400", description = "Invalid setup", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "The run already has accounts", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Account> openAccounts(@PathVariable UUID runId, @Valid @RequestBody OpenAccountsRequest request) {
        return runs.openAccounts(
            runId,
            new AccountSetup(request.accountCount(), request.openingBalanceCents(), request.blockedAccounts(), request.dailyLimitCents()));
    }

    @ApiResponse(responseCode = "200", description = "The ledger's checks; an unknown run has no accounts and zeros")
    @ApiResponse(
        responseCode = "503",
        description = "The database cancelled a reconciliation query at the statement timeout.",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/reconciliation")
    public ReconciliationReport reconciliation(@PathVariable UUID runId) {
        try {
            return runs.reconcile(runId);
        } catch (QueryTimeoutException e) {
            throw new ReconciliationTimeoutException(runId, e);
        }
    }

    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Unknown run")
    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable UUID runId) {
        return runs.deleteRun(runId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(RunAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError runExists(RunAlreadyExistsException e) {
        return new ApiError(e.getMessage() != null ? e.getMessage() : "conflict");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidSetup(IllegalArgumentException e) {
        return new ApiError(e.getMessage() != null ? e.getMessage() : "bad request");
    }

    @ExceptionHandler(ReconciliationTimeoutException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError reconciliationTimeout(ReconciliationTimeoutException e) {
        log.warn("Reconciliation of run {} hit the database statement timeout ({} ms)", e.runId(), reconciliation.statementTimeout().toMillis());
        return new ApiError(STATEMENT_TIMEOUT);
    }
}
