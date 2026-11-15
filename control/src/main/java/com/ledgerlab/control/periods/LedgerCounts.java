package com.ledgerlab.control.periods;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The period's transfers as the ledger counts them.")
public record LedgerCounts(
    long postedTransfers,
    long rejectedTransfers,
    @Schema(description = "Postings between the pool and the accounts; no client sees them.")
    long poolPostings
) {
}
