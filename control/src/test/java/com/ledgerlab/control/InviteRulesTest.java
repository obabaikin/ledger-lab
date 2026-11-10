package com.ledgerlab.control;

import static com.ledgerlab.control.entry.InviteRules.nextMail;
import static com.ledgerlab.control.entry.InviteRules.normalizedEmail;
import static com.ledgerlab.control.entry.MailTexts.minuteAfter;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.entry.AddressMails;
import com.ledgerlab.control.entry.MailKind;
import com.ledgerlab.control.entry.NextMail;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InviteRulesTest {

    private final Duration window = Duration.ofHours(24);
    private final Instant oldest = Instant.parse("2026-10-25T08:00:00Z");

    @Test
    @DisplayName("links until the limit, then one notice of when the oldest link leaves the window, then nothing")
    void links_until_the_limit_then_one_notice_of_when_the_oldest_link_leaves_the_window_then_nothing() {
        assertThat(nextMail(new AddressMails(2, 0, oldest), 3, window)).isEqualTo(new NextMail(MailKind.LINK, null));
        assertThat(nextMail(new AddressMails(3, 0, oldest), 3, window)).isEqualTo(new NextMail(MailKind.LIMIT, Instant.parse("2026-10-26T08:00:00Z")));
        assertThat(nextMail(new AddressMails(3, 1, oldest), 3, window)).isNull();
    }

    @Test
    void an_address_is_trimmed_and_lowercased() {
        assertThat(normalizedEmail("  A@B.c ")).isEqualTo("a@b.c");
        assertThat(normalizedEmail("a@b.c")).isEqualTo("a@b.c");
    }

    @Test
    @DisplayName("only the string's shape is checked, and a malformed one is no address")
    void only_the_strings_shape_is_checked_and_a_malformed_one_is_no_address() {
        List.of("", "a", "@b.c", "a@", "a@@b.c", "a@b@c", "a b@c.d", "a,b@c.d", "a@b\nc.d", "a b@c.d", "a\u0007@b.c")
            .forEach(it -> assertThat(normalizedEmail(it)).describedAs(it).isNull());
        assertThat(normalizedEmail("first.last+tag@sub.example.org")).isEqualTo("first.last+tag@sub.example.org");
    }

    @Test
    @DisplayName("the time in the limit mail is rounded up to the minute, never before the place frees")
    void the_time_in_the_limit_mail_is_rounded_up_to_the_minute_never_before_the_place_frees() {
        assertThat(minuteAfter(Instant.parse("2026-10-26T08:15:40Z"))).isEqualTo(Instant.parse("2026-10-26T08:16:00Z"));
        assertThat(minuteAfter(Instant.parse("2026-10-26T08:15:00Z"))).isEqualTo(Instant.parse("2026-10-26T08:15:00Z"));
    }
}
