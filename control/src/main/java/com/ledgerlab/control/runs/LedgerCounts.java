package com.ledgerlab.control.runs;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What the ledger applied for the run, and the sums of its books.")
public record LedgerCounts(
    long posted,
    long rejected,
    @Schema(description = "Sum of the accounts' opening balances.")
    long openingTotalCents,
    @Schema(description = "Sum of the accounts' balances after the run.")
    long balanceTotalCents,
    @Schema(description = "Sum of the journal's debits, as a positive amount.")
    long debitsCents,
    @Schema(description = "Sum of the journal's credits.")
    long creditsCents
) {
}
