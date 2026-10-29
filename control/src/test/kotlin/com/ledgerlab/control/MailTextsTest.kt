package com.ledgerlab.control

import com.ledgerlab.control.entry.MailKind
import com.ledgerlab.control.entry.MailTexts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.MessageSource
import java.time.Instant
import java.util.Locale
import java.util.UUID

class MailTextsTest : IntegrationTest() {

    @Autowired lateinit var messages: MessageSource

    private fun texts(limits: ControlProperties.InviteEmail = props.invites.email) = MailTexts(messages, props.site.baseUrl, limits, props.mail.timeZone)

    @Test
    fun `every mail kind renders`() {
        MailKind.entries.forEach { kind ->
            val text = when (kind) {
                MailKind.LINK -> texts().link(UUID.randomUUID().toString())
                MailKind.LIMIT -> texts().limit(UUID.randomUUID().toString(), Instant.parse("2026-10-28T10:15:00Z"))
            }
            assertThat(text.subject).isNotBlank()
            assertThat(text.body).isNotBlank()
        }
    }

    @Test
    fun `a quote in a mail text survives MessageFormat`() {
        assertThat(messages.getMessage("test.quote", arrayOf("here"), Locale.ENGLISH)).isEqualTo("It's here")
    }

    @Test
    fun `the limit mail names the time in Berlin, in winter and in summer`() {
        val winter = texts().limit(UUID.randomUUID().toString(), Instant.parse("2026-10-28T10:15:00Z")).body
        val summer = texts().limit(UUID.randomUUID().toString(), Instant.parse("2026-07-01T10:15:00Z")).body

        assertThat(winter).contains("Ask again after 28.10.2026 11:15 CET.\n")
        assertThat(summer).contains("Ask again after 01.07.2026 12:15 CEST.\n")
    }

    @Test
    fun `numbers in a mail are not grouped`() {
        val limits = props.invites.email.copy(linksPerAddress = 1000)

        val body = texts(limits).limit(UUID.randomUUID().toString(), Instant.parse("2026-10-28T10:15:00Z")).body

        assertThat(body).startsWith("You have asked for a link 1000 times in 24 hours, the limit.")
    }
}
