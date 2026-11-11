package com.ledgerlab.control.ledger;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

@Schema(description = "The ledger's check of a period's books, as the ledger reported it.")
public record PeriodCheck(
    long journalEntries,
    long debitsCents,
    long creditsCents,
    long transfersWithInvalidJournal,
    @Schema(description = "Ids of the first such transfers.")
    List<String> invalidJournalExamples,
    @Schema(description = "False for the bank's first period: it has no balances of a period before to compare with.")
    boolean accountsCompared,
    long accountsOutOfBalance,
    List<AccountMismatch> accountExamples,
    List<LedgerCheck> failedChecks,
    boolean balanced
) {

    public PeriodCheck {
        invalidJournalExamples = List.copyOf(Objects.requireNonNull(invalidJournalExamples, "invalidJournalExamples"));
        accountExamples = List.copyOf(Objects.requireNonNull(accountExamples, "accountExamples"));
        failedChecks = List.copyOf(Objects.requireNonNull(failedChecks, "failedChecks"));
    }
}
