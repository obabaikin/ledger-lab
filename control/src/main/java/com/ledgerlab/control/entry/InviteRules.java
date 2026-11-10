package com.ledgerlab.control.entry;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class InviteRules {

    private static final int MAX_EMAIL_LENGTH = 254;
    /** One `@` with something on each side, and no whitespace, comma or control character anywhere. */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("(?U)[^@\\s,\\p{Cntrl}]+@[^@\\s,\\p{Cntrl}]+");

    private InviteRules() {
    }

    /** A link while the window holds fewer than the limit, then one notice of when a link is possible again, then nothing. */
    public static @Nullable NextMail nextMail(AddressMails mails, int linksPerAddress, Duration window) {
        if (mails.links() < linksPerAddress) {
            return new NextMail(MailKind.LINK, null);
        }
        if (mails.limits() == 0) {
            return new NextMail(MailKind.LIMIT, Objects.requireNonNull(mails.oldestLink(), "the oldest link of an address at its limit").plus(window));
        }
        return null;
    }

    /** Only what the string itself shows; whether the mailbox exists is never told (ADR 0017). The page checks the same. */
    public static @Nullable String normalizedEmail(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        var email = trimmed(raw).toLowerCase(Locale.ROOT);
        return email.length() <= MAX_EMAIL_LENGTH && EMAIL_SHAPE.matcher(email).matches() ? email : null;
    }

    // Wider than String.strip: a no-break space pasted around an address goes too.
    private static String trimmed(String raw) {
        var start = 0;
        var end = raw.length();
        while (start < end && isSpace(raw.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(raw.charAt(end - 1))) {
            end--;
        }
        return raw.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
