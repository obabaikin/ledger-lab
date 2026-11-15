package com.ledgerlab.ledger.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The checks of a closed period that read its whole journal; made once, in the background, and kept. */
@JsonIgnoreProperties(value = {"failedChecks", "balanced"}, allowGetters = true)
public record PeriodCheck(
    long journalEntries,
    long debitsCents,
    long creditsCents,
    long transfersWithInvalidJournal,
    @Schema(description = "The first of them, up to the configured number") List<UUID> invalidJournalExamples,
    @Schema(description = "False when the period before has no snapshot of the balances: the accounts were not compared")
    boolean accountsCompared,
    long accountsOutOfBalance,
    @Schema(description = "The first of them, up to the configured number") List<AccountMismatch> accountExamples,
    @Schema(description = "When the ledger began the check") Instant startedAt,
    @Schema(description = "When the ledger kept the check") Instant madeAt
) {
    @JsonProperty
    public List<ReconciliationCheck> failedChecks() {
        var failed = new ArrayList<ReconciliationCheck>();
        if (debitsCents != creditsCents) {
            failed.add(ReconciliationCheck.JOURNAL_DEBITS_EQUAL_CREDITS);
        }
        if (transfersWithInvalidJournal != 0L) {
            failed.add(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
        }
        if (accountsOutOfBalance != 0L) {
            failed.add(ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL);
        }
        return failed;
    }

    @JsonProperty
    public boolean balanced() {
        return failedChecks().isEmpty();
    }
}
