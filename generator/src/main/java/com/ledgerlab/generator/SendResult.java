package com.ledgerlab.generator;

public enum SendResult {
    RESPONSE,
    /** No connection: the request never left. */
    NOT_CONNECTED,
    /** Timeout or broken connection after the request may have left. */
    NO_RESPONSE,
    CANCELLED,
}
