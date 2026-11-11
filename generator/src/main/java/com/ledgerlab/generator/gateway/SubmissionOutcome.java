package com.ledgerlab.generator.gateway;

import java.util.Locale;

/** Outcomes of the client contract, ADR 0006. */
public enum SubmissionOutcome {
    ACKNOWLEDGED,
    FAILED,
    UNKNOWN;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
