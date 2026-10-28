package com.ledgerlab.control.entry

import com.ledgerlab.control.ControlProperties
import org.springframework.context.MessageSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MailText(val subject: String, val body: String)

/** Arguments go in as strings: MessageFormat would group a number's digits (1,000). */
class MailTexts(private val messages: MessageSource, private val baseUrl: String, private val limits: ControlProperties.InviteEmail) {

    fun link(token: String) = MailText(
        text("mail.link.subject"),
        text("mail.link.body", "$baseUrl/?ref=$token", lifetime(), deletion(token)),
    )

    fun limit(token: String, windowFreesAt: Instant) = MailText(
        text("mail.limit.subject"),
        text(
            "mail.limit.body",
            limits.linksPerAddress.toString(),
            limits.window.toHours().toString(),
            UTC_MINUTES.format(minuteAfter(windowFreesAt)),
            lifetime(),
            deletion(token),
        ),
    )

    private fun lifetime() = text("mail.lifetime", limits.lifetime.toDays().toString(), limits.retention.toDays().toString())

    private fun deletion(token: String) = text("mail.deletion", "$baseUrl/delete?ref=$token")

    private fun text(key: String, vararg args: String): String = messages.getMessage(key, args, LOCALE)

    private companion object {
        val LOCALE: Locale = Locale.ENGLISH
        val UTC_MINUTES: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    }
}
