package com.ledgerlab.gateway.api

import io.swagger.v3.oas.annotations.media.Schema

/** Error texts of the gateway's API; clients tell the cases apart by status code, the text names it. */
object GatewayErrors {
    const val PERIOD_CLOSED = "period closed"
    const val PERIOD_NOT_CLOSED = "period not closed"
    const val CLOSE_LOCK_TIMEOUT = "inserts in flight did not finish in time"
    const val BAD_REQUEST = "bad request"
    const val CONFLICT = "conflict"
    const val BUSY = "busy"
}

@Schema(description = "Body of an error answer.")
data class ApiError(@field:Schema(description = "Names the case; the status code tells it apart.") val error: String)
