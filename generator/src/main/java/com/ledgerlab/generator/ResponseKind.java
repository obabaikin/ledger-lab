package com.ledgerlab.generator;

import java.util.Locale;

/** How one request to the bank ended, as the client saw it. */
public enum ResponseKind {
    /** 2xx: the bank has the transfer. */
    ACCEPTED(true, false),
    /** 4xx: the bank answered that the request is wrong. */
    INVALID(true, false),
    /** 503 busy: an answer, the transfer was not taken. */
    BUSY(true, false),
    /** 503 stopping: the instance was shutting down; not taken, but the client was not served. */
    STOPPING(true, true),
    SERVER_ERROR(true, true),
    /** No connection: the request never left. */
    NOT_CONNECTED(true, true),
    /** Timeout or broken connection after the request may have left. */
    NO_RESPONSE(true, true),
    /** The client itself failed to send; says nothing about the bank. */
    SEND_ERROR(false, false),
    /** Cut short by the generator's own stop. */
    CANCELLED(false, false);

    private final boolean aboutTheBank;
    private final boolean error;

    ResponseKind(boolean aboutTheBank, boolean error) {
        this.aboutTheBank = aboutTheBank;
        this.error = error;
    }

    /** Whether the request counts when the bank's availability is measured. */
    public boolean isAboutTheBank() {
        return aboutTheBank;
    }

    /** The client got no answer it could use: a 5xx other than busy, or no answer at all. */
    public boolean isError() {
        return error;
    }

    public String tag() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
