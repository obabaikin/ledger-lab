package com.ledgerlab.generator;

import io.swagger.v3.oas.annotations.media.Schema;

public record JournalTotals(
    long requested,
    long acknowledged,
    long failed,
    long unknown,
    long acknowledgedAmountCents,
    @Schema(description = REFUSED_DESCRIPTION)
    long refused
) {
    public static final String REFUSED_DESCRIPTION = "Transfers the gateway refused as busy (503) at least once, retried or not; each counts once.";
}
