package com.ledgerlab.control.entry;

import com.ledgerlab.control.ControlProperties;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.context.MessageSource;

/** Arguments go in as strings: MessageFormat would group a number's digits (1,000). */
public final class MailTexts {

    private static final Locale LOCALE = Locale.ENGLISH;

    private final MessageSource messages;
    private final String baseUrl;
    private final ControlProperties.InviteEmail limits;
    private final DateTimeFormatter time;

    public MailTexts(MessageSource messages, String baseUrl, ControlProperties.InviteEmail limits, ZoneId zone) {
        this.messages = messages;
        this.baseUrl = baseUrl;
        this.limits = limits;
        this.time = DateTimeFormatter.ofPattern(text("mail.time"), LOCALE).withZone(zone);
    }

    public MailText link(String token) {
        return new MailText(
            text("mail.link.subject"),
            text("mail.link.body", baseUrl + "/?ref=" + token, lifetime(), deletion(token)));
    }

    public MailText limit(String token, Instant windowFreesAt) {
        return new MailText(
            text("mail.limit.subject"),
            text(
                "mail.limit.body",
                Integer.toString(limits.linksPerAddress()),
                Long.toString(limits.window().toHours()),
                time.format(minuteAfter(windowFreesAt)),
                lifetime(),
                deletion(token)));
    }

    /** Rounded up: a time cut down to the minute could name a moment before the place frees. */
    public static Instant minuteAfter(Instant at) {
        var minute = at.truncatedTo(ChronoUnit.MINUTES);
        return minute.equals(at) ? at : minute.plus(1, ChronoUnit.MINUTES);
    }

    private String lifetime() {
        return text("mail.lifetime", Long.toString(limits.lifetime().toDays()), Long.toString(limits.retention().toDays()));
    }

    private String deletion(String token) {
        return text("mail.deletion", baseUrl + "/delete?ref=" + token);
    }

    private String text(String key, String... args) {
        return messages.getMessage(key, args, LOCALE);
    }
}
