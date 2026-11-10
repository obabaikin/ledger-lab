package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.entry.MailSender;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "control.mail.api-key=")
class NoMailKeyTest extends IntegrationTest {

    @Autowired MailSender sender;

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("without a mail key a mail is logged as not sent, without its address or token")
    void without_a_mail_key_a_mail_is_logged_as_not_sent_without_its_address_or_token(CapturedOutput output) {
        requestLink("visitor@example.org");
        var mail = jdbc.sql("SELECT mail_id, token FROM invite_mails").query((rs, row) -> Map.entry(rs.getLong(1), rs.getString(2))).single();
        var mailId = mail.getKey();
        var token = mail.getValue();

        sender.sendDue();

        assertThat(mailjet.calls).isEmpty();
        assertThat(output.getOut()).contains("Mail " + mailId + " LINK not sent: no mail key").doesNotContain("visitor@example.org").doesNotContain(token);
        assertThat(jdbc.sql("SELECT failed_at IS NOT NULL FROM invite_mails").query(Boolean.class).single()).isTrue();
    }
}
