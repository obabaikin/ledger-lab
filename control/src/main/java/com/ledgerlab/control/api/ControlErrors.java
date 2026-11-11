package com.ledgerlab.control.api;


/** The older phrases the page tells apart by status code; the kebab-case codes it reads to pick its text. */
public final class ControlErrors {
    public static final String INVITE_NOT_VALID = "invite not valid";
    public static final String SESSION_NOT_FOUND = "session not found";
    public static final String INVALID_REQUEST_BODY = "invalid request body";
    public static final String CHAOS_NOT_AVAILABLE = "chaos not available";
    public static final String CHAOS_USED_RECENTLY = "chaos used recently";
    public static final String LEDGER_DID_NOT_ANSWER = "ledger did not answer";
    public static final String BUSY = "busy";

    public static final String INVALID_EMAIL = "invalid-email";
    public static final String VERIFICATION_FAILED = "verification-failed";
    public static final String DAILY_LIMIT = "daily-limit";
    public static final String TOKEN_REQUIRED = "token-required";
    public static final String UNKNOWN_LINK = "unknown-link";
    public static final String UNKNOWN_PERIOD = "unknown-period";
    public static final String INVALID_LIMIT = "invalid-limit";
    public static final String INVALID_RANGE = "invalid-range";

    private ControlErrors() {
    }
}
