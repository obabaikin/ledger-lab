package com.ledgerlab.control.ledger;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "All balances of the bank against the sum its accounts were opened with.")
public record Books(long accounts, long openingTotalCents, long balanceTotalCents, boolean balanced) {
}
