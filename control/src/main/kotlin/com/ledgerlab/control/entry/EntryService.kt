package com.ledgerlab.control.entry

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.runs.QUOTA_WINDOW
import com.ledgerlab.control.runs.RunRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class SessionOverview(
    val sessionId: UUID,
    val inviteValid: Boolean,
    val runsPerHour: Int,
    val runsLastHour: Long,
    val tpsOptions: List<Int>,
    val durationOptions: List<Int>,
)

class InviteNotValidException : RuntimeException()

class SessionNotFoundException : RuntimeException()

@Service
class EntryService(
    private val invites: InviteRepository,
    private val runs: RunRepository,
    private val props: ControlProperties,
) {
    /** Null rather than an exception: an exception would roll back the visit of an expired invite. */
    @Transactional
    fun enter(token: String?): UUID? {
        if (token.isNullOrBlank()) return null
        val invite = invites.find(token) ?: return null
        invites.recordVisit(token)
        return if (invite.valid) invites.createSession(token) else null
    }

    @Transactional(readOnly = true)
    fun overview(sessionId: UUID): SessionOverview {
        val session = invites.findSession(sessionId) ?: throw SessionNotFoundException()
        val invite = checkNotNull(invites.find(session.token))
        return SessionOverview(
            sessionId = sessionId,
            inviteValid = invite.valid,
            runsPerHour = invite.quota,
            runsLastHour = runs.quotaUsage(session.token, QUOTA_WINDOW).used,
            tpsOptions = props.runs.tpsOptions,
            durationOptions = props.runs.durationSecondsOptions,
        )
    }
}
