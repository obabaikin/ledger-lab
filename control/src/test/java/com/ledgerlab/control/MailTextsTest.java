package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.entry.MailKind;
import com.ledgerlab.control.entry.MailTexts;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

class MailTextsTest extends IntegrationTest {

    @Autowired MessageSource messages;

    private MailTexts texts() {
        return texts(props.invites().email());
    }

    private MailTexts texts(ControlProperties.InviteEmail limits) {
        return new MailTexts(messages, props.site().baseUrl(), limits, props.mail().timeZone());
    }

    @Test
    void every_mail_kind_renders() {
        for (var kind : MailKind.values()) {
            var text = switch (kind) {
                case LINK -> texts().link(UUID.randomUUID().toString());
                case LIMIT -> texts().limit(UUID.randomUUID().toString(), Instant.parse("2026-10-28T10:15:00Z"));
            };
            assertThat(text.subject()).isNotBlank();
            assertThat(text.body()).isNotBlank();
        }
    }

    @Test
    void a_quote_in_a_mail_text_survives_MessageFormat() {
        assertThat(messages.getMessage("test.quote", new Object[] {"here"}, Locale.ENGLISH)).isEqualTo("It's here");
    }

    @Test
    @DisplayName("the limit mail names the time in Berlin, in winter and in summer")
    void the_limit_mail_names_the_time_in_Berlin_in_winter_and_in_summer() {
        var winter = texts().limit(UUID.randomUUID().toString(), Instant.parse("2026-10-28T10:15:00Z")).body();
        var summer = texts().limit(UUID.randomUUID().toString(), Instant.parse("2026-07-01T10:15:00Z")).body();

        assertThat(winter).contains("Ask again after 28.10.2026 11:15 CET.\n");
        assertThat(summer).contains("Ask again after 01.07.2026 12:15 CEST.\n");
    }

    @Test
    void numbers_in_a_mail_are_not_grouped() {
        var email = props.invites().email();
        var limits = new ControlProperties.InviteEmail(
            1000, email.dailyMails(), email.window(), email.lifetime(), email.runsPerHour(), email.retention(), email.deletionCheck());

        var body = texts(limits).limit(UUID.randomUUID().toString(), Instant.parse("2026-10-28T10:15:00Z")).body();

        assertThat(body).startsWith("You have asked for a link 1000 times in 24 hours, the limit.");
    }
}
