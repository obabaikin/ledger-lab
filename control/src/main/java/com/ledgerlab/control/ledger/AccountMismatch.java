package com.ledgerlab.control.ledger;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An account whose balance moved by another sum than its journal entries of the period.")
public record AccountMismatch(int accountNo, long journalCents, long balanceChangeCents) {
}
