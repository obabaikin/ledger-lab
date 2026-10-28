package com.ledgerlab.control.api

import com.ledgerlab.control.entry.InviteRequestService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Schema(description = "An address to mail an invite link to.")
data class InviteRequest(
    @field:Schema(description = "Where the link goes; trimmed and lowercased.", requiredMode = Schema.RequiredMode.REQUIRED)
    val email: String?,
    @field:Schema(description = "The Turnstile widget's answer; null where the check is off.")
    val turnstileToken: String?,
)

@Schema(description = "Taken; the same for any address, whether a mail goes out or not.")
class InviteRequestAccepted

@RestController
@RequestMapping("/api/invite-requests")
class InviteRequestController(private val requests: InviteRequestService) {

    @Operation(summary = "Mail an invite link to an address")
    @ApiResponse(responseCode = "202", description = "Taken; a mail goes out if the address's limit allows")
    @ApiResponse(responseCode = "400", description = "Not an address, or unreadable body", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "403", description = "Verification failed; nothing recorded", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(
        responseCode = "429", description = "The daily limit of all mails is used up; nothing recorded",
        headers = [Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds until a mail fits the limit", schema = Schema(type = "integer"))],
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @ApiResponse(responseCode = "503", description = "Verification did not answer; nothing recorded", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun request(@RequestBody request: InviteRequest): InviteRequestAccepted {
        requests.request(request.email, request.turnstileToken)
        return InviteRequestAccepted()
    }
}
