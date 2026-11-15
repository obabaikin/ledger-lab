package com.ledgerlab.ledger.domain;

import io.swagger.v3.oas.annotations.media.Schema;

public record AccountMismatch(
    int accountNo,
    @Schema(description = "Sum of the account's journal entries of the period") long journalCents,
    @Schema(description = "Balance at the end of the period minus the balance at its start") long balanceChangeCents
) {
}
