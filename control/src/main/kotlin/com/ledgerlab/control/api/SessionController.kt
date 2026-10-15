package com.ledgerlab.control.api

import com.ledgerlab.control.entry.EntryService
import com.ledgerlab.control.entry.InviteNotValidException
import com.ledgerlab.control.entry.SessionOverview
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@Schema(description = "The invite from the link.")
data class CreateSessionRequest(
    @field:Schema(description = "Token of the invite link.", requiredMode = Schema.RequiredMode.REQUIRED)
    val token: String?,
)

@Schema(description = "A new session; its id is the key to it.")
data class SessionCreated(val sessionId: UUID)

@Schema(description = "What a session may do now.")
data class SessionView(
    val sessionId: UUID,
    @field:Schema(description = "False once the invite expired or was switched off; runs can then only be read.")
    val inviteValid: Boolean,
    @field:Schema(description = "Runs the invite may start in any hour.")
    val runsPerHour: Int,
    @field:Schema(description = "Runs started with the invite in the last hour, from any session.")
    val runsLastHour: Long,
    @field:Schema(description = "Allowed transfers per second.")
    val tpsOptions: List<Int>,
    @field:Schema(description = "Allowed run durations, seconds.")
    val durationOptions: List<Int>,
    @field:Schema(description = "Whether the chaos button works in this deployment.")
    val chaosAvailable: Boolean,
    @field:Schema(description = "Seconds until chaos may be used again, on the whole cluster; 0 when it may be used now.")
    val chaosReadyInSeconds: Long,
)

@RestController
@RequestMapping(SessionController.SESSIONS_PATH)
class SessionController(private val entry: EntryService) {

    @Operation(summary = "Open a session with an invite")
    @ApiResponse(responseCode = "201", description = "Session opened")
    @ApiResponse(responseCode = "400", description = "Unreadable body", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "403", description = "Unknown, inactive or expired invite", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping
    fun create(@RequestBody request: CreateSessionRequest): ResponseEntity<SessionCreated> {
        val sessionId = entry.enter(request.token) ?: throw InviteNotValidException()
        return ResponseEntity.created(URI.create("$SESSIONS_PATH/$sessionId")).body(SessionCreated(sessionId))
    }

    @Operation(summary = "A session's quota and run options")
    @ApiResponse(responseCode = "200", description = "The session")
    @ApiResponse(responseCode = "404", description = "Unknown session", content = [Content(schema = Schema(implementation = ApiError::class))])
    @GetMapping("/{sessionId}")
    fun get(@PathVariable sessionId: UUID): SessionView = entry.overview(sessionId).toView()

    companion object {
        const val SESSIONS_PATH = "/api/sessions"
    }
}

private fun SessionOverview.toView() = SessionView(sessionId, inviteValid, runsPerHour, runsLastHour, tpsOptions, durationOptions, chaosAvailable, chaosReadyInSeconds)
