package com.ledgerlab.control

import com.ledgerlab.control.entry.MailSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["control.mail.api-key="])
class NoMailKeyTest : IntegrationTest() {

    @Autowired lateinit var sender: MailSender

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `without a mail key a mail is logged as not sent, without its address or token`(output: CapturedOutput) {
        requestLink("visitor@example.org")
        val (mailId, token) = jdbc.sql("SELECT mail_id, token FROM invite_mails").query { rs, _ -> rs.getLong(1) to rs.getString(2) }.single()

        sender.sendDue()

        assertThat(mailjet.calls).isEmpty()
        assertThat(output.out).contains("Mail $mailId LINK not sent: no mail key").doesNotContain("visitor@example.org").doesNotContain(token)
        assertThat(jdbc.sql("SELECT failed_at IS NOT NULL FROM invite_mails").query(Boolean::class.java).single()).isTrue()
    }
}
