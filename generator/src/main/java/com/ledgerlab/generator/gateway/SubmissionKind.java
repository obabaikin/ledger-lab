package com.ledgerlab.generator.gateway;

import java.util.Locale;

/** What a send was, from the client's side; the gateway cannot tell a retry from a duplicate. */
public enum SubmissionKind {
    FIRST,
    RETRY,
    DUPLICATE;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
