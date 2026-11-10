package com.ledgerlab.control.api;

import java.util.List;

/** The older phrases the page tells apart by status code; the kebab-case codes it reads to pick its text. */
public final class ControlErrors {
    public static final String INVITE_NOT_VALID = "invite not valid";
    public static final String SESSION_NOT_FOUND = "session not found";
    public static final String RUN_NOT_FOUND = "run not found";
    public static final String REQUEST_ID_CONFLICT = "requestId already used with a different request";
    public static final String RUN_NOT_FINAL = "a run of this session is not final";
    public static final String RUN_QUOTA_EXCEEDED = "run quota exceeded";
    public static final String RUN_QUEUE_FULL = "run queue full";
    public static final String REQUEST_ID_REQUIRED = "requestId is required";
    public static final String INVALID_REQUEST_BODY = "invalid request body";
    public static final String RUN_NOT_STARTED = "run not started";
    public static final String REPORT_NOT_READY = "report not ready";
    public static final String CHAOS_NOT_AVAILABLE = "chaos not available";
    public static final String NO_RUNNING_RUN = "no running run";
    public static final String CHAOS_USED_RECENTLY = "chaos used recently";
    public static final String LEDGER_DID_NOT_ANSWER = "ledger did not answer";
    public static final String BUSY = "busy";

    public static final String INVALID_EMAIL = "invalid-email";
    public static final String VERIFICATION_FAILED = "verification-failed";
    public static final String DAILY_LIMIT = "daily-limit";
    public static final String TOKEN_REQUIRED = "token-required";
    public static final String UNKNOWN_LINK = "unknown-link";

    private ControlErrors() {
    }

    public static String tpsNotAllowed(List<Integer> options) {
        return "tps must be one of " + options;
    }

    public static String durationNotAllowed(List<Integer> options) {
        return "durationSeconds must be one of " + options;
    }
}
