package com.ledgerlab.control.api

import io.swagger.v3.oas.annotations.media.Schema

/** The older phrases the page tells apart by status code; the kebab-case codes it reads to pick its text. */
object ControlErrors {
    const val INVITE_NOT_VALID = "invite not valid"
    const val SESSION_NOT_FOUND = "session not found"
    const val RUN_NOT_FOUND = "run not found"
    const val REQUEST_ID_CONFLICT = "requestId already used with a different request"
    const val RUN_NOT_FINAL = "a run of this session is not final"
    const val RUN_QUOTA_EXCEEDED = "run quota exceeded"
    const val RUN_QUEUE_FULL = "run queue full"
    const val REQUEST_ID_REQUIRED = "requestId is required"
    const val INVALID_REQUEST_BODY = "invalid request body"
    const val RUN_NOT_STARTED = "run not started"
    const val REPORT_NOT_READY = "report not ready"
    const val CHAOS_NOT_AVAILABLE = "chaos not available"
    const val NO_RUNNING_RUN = "no running run"
    const val CHAOS_USED_RECENTLY = "chaos used recently"
    const val LEDGER_DID_NOT_ANSWER = "ledger did not answer"
    const val BUSY = "busy"
    const val INVALID_EMAIL = "invalid-email"
    const val VERIFICATION_FAILED = "verification-failed"
    const val DAILY_LIMIT = "daily-limit"
    const val TOKEN_REQUIRED = "token-required"
    const val UNKNOWN_LINK = "unknown-link"

    fun tpsNotAllowed(options: List<Int>) = "tps must be one of $options"
    fun durationNotAllowed(options: List<Int>) = "durationSeconds must be one of $options"
}

@Schema(description = "Body of an error answer.")
data class ApiError(@field:Schema(description = "Names the case; a kebab-case code tells it apart, a phrase together with the status code.") val error: String)
