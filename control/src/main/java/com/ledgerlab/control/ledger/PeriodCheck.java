package com.ledgerlab.control.ledger;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@Schema(description = "The ledger's check of a period's books, as the ledger reported it.")
public record PeriodCheck(
    long journalEntries,
    long debitsCents,
    long creditsCents,
    long transfersWithInvalidJournal,
    @Schema(description = "Ids of the first such transfers.")
    List<String> invalidJournalExamples,
    @Schema(description = "False where the ledger holds no balances of the period before to compare with: the bank's first period, and one after a period that failed.")
    boolean accountsCompared,
    long accountsOutOfBalance,
    List<AccountMismatch> accountExamples,
    List<LedgerCheck> failedChecks,
    boolean balanced,
    @Schema(description = "When the ledger began the check; null in reports kept before the ledger told it.", nullable = true)
    @Nullable Instant startedAt,
    @Schema(description = "When the ledger kept the check; null in reports kept before the ledger told it.", nullable = true)
    @Nullable Instant madeAt
) {

    public PeriodCheck {
        invalidJournalExamples = List.copyOf(Objects.requireNonNull(invalidJournalExamples, "invalidJournalExamples"));
        accountExamples = List.copyOf(Objects.requireNonNull(accountExamples, "accountExamples"));
        failedChecks = List.copyOf(Objects.requireNonNull(failedChecks, "failedChecks"));
    }
}
