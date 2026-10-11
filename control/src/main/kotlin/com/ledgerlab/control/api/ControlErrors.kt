package com.ledgerlab.control.api

import io.swagger.v3.oas.annotations.media.Schema

/** Error texts of control's API; the page tells the cases apart by status code, the text names it. */
object ControlErrors {
    const val INVITE_NOT_VALID = "invite not valid"
    const val SESSION_NOT_FOUND = "session not found"
    const val RUN_NOT_FOUND = "run not found"
    const val REQUEST_ID_CONFLICT = "requestId already used with a different request"
    const val RUN_QUOTA_EXCEEDED = "run quota exceeded"
    const val RUN_QUEUE_FULL = "run queue full"
    const val REQUEST_ID_REQUIRED = "requestId is required"
    const val INVALID_REQUEST_BODY = "invalid request body"

    fun tpsNotAllowed(options: List<Int>) = "tps must be one of $options"
    fun durationNotAllowed(options: List<Int>) = "durationSeconds must be one of $options"
}

@Schema(description = "Body of an error answer.")
data class ApiError(@field:Schema(description = "Names the case; the status code tells it apart.") val error: String)
