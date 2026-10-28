package com.ledgerlab.control.entry

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.http.Reply
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The log names a mail by its id and kind only; its address and token stay in the database. */
@Component
class MailSender(
    private val mails: InviteMailRepository,
    private val mailjet: MailjetClient,
    private val props: ControlProperties,
    private val tx: TransactionTemplate,
    messages: MessageSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val texts = MailTexts(messages, props.site.baseUrl, props.invites.email)

    @Scheduled(fixedDelayString = "\${control.mail.send-interval}", initialDelayString = "\${control.mail.send-interval}")
    fun sendDue(): Int {
        var handled = 0
        while (handled < props.mail.batchSize && tx.execute { mails.claimDue(1).firstOrNull()?.also(::handle) } != null) handled++
        return handled
    }

    private fun handle(mail: Mail) {
        val email = mail.email
        when {
            email == null -> {
                mails.markFailed(mail.mailId)
                log.warn("Mail {} {} not sent: its address was deleted", mail.mailId, mail.kind)
            }
            !mailjet.configured -> {
                mails.markFailed(mail.mailId)
                log.info("Mail {} {} not sent: no mail key", mail.mailId, mail.kind)
            }
            else -> deliver(mail, email)
        }
    }

    private fun deliver(mail: Mail, email: String) {
        val text = when (mail.kind) {
            MailKind.LINK -> texts.link(mail.token)
            MailKind.LIMIT -> texts.limit(checkNotNull(mail.windowFreesAt))
        }
        val reply = mailjet.send(email, text.subject, text.body)
        val attempt = mail.attempts + 1
        when {
            reply is Reply.Answered -> {
                mails.markSent(mail.mailId)
                log.info("Mail {} {} sent", mail.mailId, mail.kind)
            }
            reply is Reply.Refused && reply.status != HttpStatus.TOO_MANY_REQUESTS.value() && reply.status < SERVER_ERROR -> {
                mails.markFailed(mail.mailId)
                log.warn("Mail {} {} refused by the provider with HTTP {}", mail.mailId, mail.kind, reply.status)
            }
            attempt >= props.mail.maxAttempts -> {
                mails.markFailed(mail.mailId)
                log.warn("Mail {} {} not sent after {} attempts: {}", mail.mailId, mail.kind, attempt, reply.outcome())
            }
            else -> {
                val pause = props.mail.firstRetry.multipliedBy(1L shl (attempt - 1))
                mails.retryLater(mail.mailId, pause)
                log.warn("Mail {} {} not sent, attempt {}: {}; retried in {}", mail.mailId, mail.kind, attempt, reply.outcome(), pause)
            }
        }
    }

    private fun Reply<*>.outcome(): String = when (this) {
        is Reply.Refused -> "HTTP $status"
        else -> javaClass.simpleName
    }

    private companion object {
        const val SERVER_ERROR = 500
    }
}

/** Rounded up: a time cut down to the minute could name a moment before the place frees. */
fun minuteAfter(at: Instant): Instant {
    val minute = at.truncatedTo(ChronoUnit.MINUTES)
    return if (minute == at) at else minute.plus(1, ChronoUnit.MINUTES)
}
