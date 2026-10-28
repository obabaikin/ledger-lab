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

    private fun texts(limits: ControlProperties.InviteEmail = props.invites.email) = MailTexts(messages, props.site.baseUrl, limits)

    @Test
    fun `every mail kind renders`() {
        MailKind.entries.forEach { kind ->
            val text = when (kind) {
                MailKind.LINK -> texts().link(UUID.randomUUID().toString())
                MailKind.LIMIT -> texts().limit(Instant.parse("2026-10-28T10:15:00Z"))
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
    fun `numbers in a mail are not grouped`() {
        val limits = props.invites.email.copy(linksPerAddress = 1000)

        val body = texts(limits).limit(Instant.parse("2026-10-28T10:15:00Z")).body

        assertThat(body).startsWith("You have asked for a link 1000 times in 24 hours, the limit.")
    }
}
