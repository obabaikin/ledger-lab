package com.ledgerlab.control.entry

import com.ledgerlab.control.ControlProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

class InvalidEmailException : RuntimeException()

class DailyLimitException(val retryAfterSeconds: Long) : RuntimeException()

data class NextMail(val kind: MailKind, val windowFreesAt: Instant?)

/** A link while the window holds fewer than the limit, then one notice of when a link is possible again, then nothing. */
fun nextMail(mails: AddressMails, linksPerAddress: Int, window: Duration): NextMail? = when {
    mails.links < linksPerAddress -> NextMail(MailKind.LINK, null)
    mails.limits == 0 -> NextMail(MailKind.LIMIT, checkNotNull(mails.oldestLink) + window)
    else -> null
}

/** Only what the string itself shows; whether the mailbox exists is never told (ADR 0017). The page checks the same. */
fun normalizedEmail(raw: String?): String? =
    raw?.trim()?.lowercase()?.takeIf { it.length <= MAX_EMAIL_LENGTH && EMAIL_SHAPE.matches(it) }

private const val MAX_EMAIL_LENGTH = 254

/** One `@` with something on each side, and no whitespace, comma or control character anywhere. */
private val EMAIL_SHAPE = Regex("""(?U)[^@\s,\p{Cntrl}]+@[^@\s,\p{Cntrl}]+""")

@Service
class InviteRequestService(
    private val mails: InviteMailRepository,
    private val turnstile: TurnstileClient,
    private val props: ControlProperties,
    private val tx: TransactionTemplate,
) {
    private val limits = props.invites.email

    /** The answer is the same whatever the address gets, so that it tells nothing about the address. */
    fun request(rawEmail: String?, turnstileToken: String?) {
        val email = normalizedEmail(rawEmail) ?: throw InvalidEmailException()
        turnstile.verify(turnstileToken)
        tx.executeWithoutResult { record(email) }
    }

    private fun record(email: String) {
        mails.lock()
        val now = mails.now()
        val since = now - limits.window
        val daily = mails.dailyMails(since)
        if (daily.count >= limits.dailyMails) {
            throw DailyLimitException(secondsUntil(checkNotNull(daily.oldest) + limits.window, now))
        }
        val invite = mails.inviteOf(email)
        val next = if (invite == null) NextMail(MailKind.LINK, null) else nextMail(mails.addressMails(email, since), limits.linksPerAddress, limits.window)
        val token = when {
            next?.kind != MailKind.LINK -> checkNotNull(invite).token
            invite?.valid == true -> invite.token.also { mails.extendInvite(it, now, limits.lifetime) }
            else -> newInvite(email, now)
        }
        mails.recordRequest(email, now)
        next?.let { mails.insertMail(email, token, it.kind, now, it.windowFreesAt) }
    }

    private fun newInvite(email: String, now: Instant): String {
        val token = "$MAILED_TOKEN_PREFIX${UUID.randomUUID()}"
        mails.createInvite(token, now, limits.lifetime, limits.runsPerHour)
        mails.setAddressInvite(email, token, now)
        return token
    }

    private fun secondsUntil(at: Instant, now: Instant): Long =
        Duration.between(now, at).plusMillis(MILLIS_PER_SECOND - 1).toSeconds().coerceAtLeast(1)

    private companion object {
        /** Tells mailed invites from the hand-made `look-` ones. */
        const val MAILED_TOKEN_PREFIX = "mail-"
        const val MILLIS_PER_SECOND = 1000L
    }
}
