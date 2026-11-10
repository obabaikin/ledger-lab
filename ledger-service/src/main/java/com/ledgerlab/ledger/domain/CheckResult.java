package com.ledgerlab.ledger.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CheckResult(ReconciliationCheck check, boolean passed) {

    @JsonProperty
    public String description() {
        return check.getDescription();
    }
}
