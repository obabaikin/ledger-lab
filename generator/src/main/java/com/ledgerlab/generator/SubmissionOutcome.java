package com.ledgerlab.generator;

import java.util.Locale;

/** Outcomes of the client contract, ADR 0006. */
public enum SubmissionOutcome {
    ACKNOWLEDGED,
    FAILED,
    UNKNOWN;

    String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
