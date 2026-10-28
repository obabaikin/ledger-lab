package com.ledgerlab.control

import com.ledgerlab.control.entry.AddressMails
import com.ledgerlab.control.entry.MailKind
import com.ledgerlab.control.entry.NextMail
import com.ledgerlab.control.entry.minuteAfter
import com.ledgerlab.control.entry.nextMail
import com.ledgerlab.control.entry.normalizedEmail
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InviteRulesTest {

    private val window = Duration.ofHours(24)
    private val oldest = Instant.parse("2026-10-25T08:00:00Z")

    @Test
    fun `links until the limit, then one notice of when the oldest link leaves the window, then nothing`() {
        assertThat(nextMail(AddressMails(2, 0, oldest), 3, window)).isEqualTo(NextMail(MailKind.LINK, null))
        assertThat(nextMail(AddressMails(3, 0, oldest), 3, window)).isEqualTo(NextMail(MailKind.LIMIT, Instant.parse("2026-10-26T08:00:00Z")))
        assertThat(nextMail(AddressMails(3, 1, oldest), 3, window)).isNull()
    }

    @Test
    fun `an address is trimmed and lowercased`() {
        assertThat(normalizedEmail("  A@B.c ")).isEqualTo("a@b.c")
        assertThat(normalizedEmail("a@b.c")).isEqualTo("a@b.c")
    }

    @Test
    fun `only the string's shape is checked, and a malformed one is no address`() {
        listOf("", "a", "@b.c", "a@", "a@@b.c", "a@b@c", "a b@c.d", "a,b@c.d", "a@b\nc.d", "a\u00a0b@c.d", "a\u0007@b.c")
            .forEach { assertThat(normalizedEmail(it)).describedAs(it).isNull() }
        assertThat(normalizedEmail("first.last+tag@sub.example.org")).isEqualTo("first.last+tag@sub.example.org")
    }

    @Test
    fun `the time in the limit mail is rounded up to the minute, never before the place frees`() {
        assertThat(minuteAfter(Instant.parse("2026-10-26T08:15:40Z"))).isEqualTo(Instant.parse("2026-10-26T08:16:00Z"))
        assertThat(minuteAfter(Instant.parse("2026-10-26T08:15:00Z"))).isEqualTo(Instant.parse("2026-10-26T08:15:00Z"))
    }
}
