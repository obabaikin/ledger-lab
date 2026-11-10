package com.ledgerlab.ledger.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record Books(
    long accounts,
    long openingTotalCents,
    @Schema(description = "All balances, the pool's included, plus the credits not yet applied") long balanceTotalCents
) {
    @JsonProperty
    public boolean balanced() {
        return balanceTotalCents == openingTotalCents;
    }
}
