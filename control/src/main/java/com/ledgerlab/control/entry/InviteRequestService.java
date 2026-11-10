package com.ledgerlab.control.entry;

import com.ledgerlab.control.ControlProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InviteRequestService {

    /** Tells mailed invites from the hand-made `look-` ones. */
    private static final String MAILED_TOKEN_PREFIX = "mail-";
    private static final long MILLIS_PER_SECOND = 1000L;

    private final InviteMailRepository mails;
    private final TurnstileClient turnstile;
    private final TransactionTemplate tx;
    private final ControlProperties.InviteEmail limits;

    public InviteRequestService(InviteMailRepository mails, TurnstileClient turnstile, ControlProperties props, TransactionTemplate tx) {
        this.mails = mails;
        this.turnstile = turnstile;
        this.tx = tx;
        this.limits = props.invites().email();
    }

    /** The answer is the same whatever the address gets, so that it tells nothing about the address. */
    public void request(@Nullable String rawEmail, @Nullable String turnstileToken) throws InterruptedException {
        var email = InviteRules.normalizedEmail(rawEmail);
        if (email == null) {
            throw new InvalidEmailException();
        }
        turnstile.verify(turnstileToken);
        tx.executeWithoutResult(status -> record(email));
    }

    private void record(String email) {
        mails.lock();
        var now = mails.now();
        var since = now.minus(limits.window());
        var daily = mails.dailyMails(since);
        if (daily.count() >= limits.dailyMails()) {
            var oldest = Objects.requireNonNull(daily.oldest(), "the oldest mail of a day at its limit");
            throw new DailyLimitException(secondsUntil(oldest.plus(limits.window()), now));
        }
        var invite = mails.inviteOf(email);
        var next = invite == null
            ? new NextMail(MailKind.LINK, null)
            : InviteRules.nextMail(mails.addressMails(email, since), limits.linksPerAddress(), limits.window());
        String token;
        if (next == null || next.kind() != MailKind.LINK) {
            token = Objects.requireNonNull(invite, "the invite of an address at its limit").token();
        } else if (invite != null && invite.valid()) {
            token = invite.token();
            mails.extendInvite(token, now, limits.lifetime());
        } else {
            token = newInvite(email, now);
        }
        mails.recordRequest(email, now);
        if (next != null) {
            mails.insertMail(email, token, next.kind(), now, next.windowFreesAt());
        }
    }

    private String newInvite(String email, Instant now) {
        var token = MAILED_TOKEN_PREFIX + UUID.randomUUID();
        mails.createInvite(token, now, limits.lifetime(), limits.runsPerHour());
        mails.setAddressInvite(email, token, now);
        return token;
    }

    private static long secondsUntil(Instant at, Instant now) {
        return Math.max(Duration.between(now, at).plusMillis(MILLIS_PER_SECOND - 1).toSeconds(), 1);
    }
}
