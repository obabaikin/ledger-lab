package com.ledgerlab.generator;

import java.util.Locale;

/** What a send was, from the client's side; the gateway cannot tell a retry from a duplicate. */
public enum SendKind {
    FIRST,
    RETRY,
    DUPLICATE;

    String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
