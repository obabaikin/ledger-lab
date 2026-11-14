package com.ledgerlab.control.ledger;

import org.jspecify.annotations.Nullable;

/** The ledger's PeriodCheckState: {@link #check} is null until the ledger has made it. */
public record LedgerCheckState(@Nullable PeriodCheck check) {
}
