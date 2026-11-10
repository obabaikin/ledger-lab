package com.ledgerlab.control.entry;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** The log names a mail by its id and kind only; its address and token stay in the database. */
@Component
public class MailSender {

    private static final Logger log = LoggerFactory.getLogger(MailSender.class);

    private final InviteMailRepository mails;
    private final MailjetClient mailjet;
    private final ControlProperties props;
    private final TransactionTemplate tx;
    private final MailTexts texts;

    public MailSender(InviteMailRepository mails, MailjetClient mailjet, ControlProperties props, TransactionTemplate tx, MessageSource messages) {
        this.mails = mails;
        this.mailjet = mailjet;
        this.props = props;
        this.tx = tx;
        this.texts = new MailTexts(messages, props.site().baseUrl(), props.invites().email(), props.mail().timeZone());
    }

    @Scheduled(fixedDelayString = "${control.mail.send-interval}", initialDelayString = "${control.mail.send-interval}")
    public int sendDue() {
        var handled = 0;
        while (handled < props.mail().batchSize() && Boolean.TRUE.equals(tx.execute(status -> handleNext()))) {
            handled++;
        }
        return handled;
    }

    private boolean handleNext() {
        var due = mails.claimDue(1);
        if (due.isEmpty()) {
            return false;
        }
        try {
            handle(due.getFirst());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UndeclaredThrowableException(e);
        }
        return true;
    }

    private void handle(Mail mail) throws InterruptedException {
        var email = mail.email();
        if (email == null) {
            mails.markFailed(mail.mailId());
            log.warn("Mail {} {} not sent: its address was deleted", mail.mailId(), mail.kind());
        } else if (!mailjet.configured()) {
            mails.markFailed(mail.mailId());
            log.info("Mail {} {} not sent: no mail key", mail.mailId(), mail.kind());
        } else {
            deliver(mail, email);
        }
    }

    private void deliver(Mail mail, String email) throws InterruptedException {
        var text = switch (mail.kind()) {
            case LINK -> texts.link(mail.token());
            case LIMIT -> texts.limit(mail.token(), Objects.requireNonNull(mail.windowFreesAt(), "windowFreesAt of a limit mail"));
        };
        var reply = mailjet.send(email, text.subject(), text.body());
        var attempt = mail.attempts() + 1;
        if (reply instanceof Reply.Answered<Object>) {
            mails.markSent(mail.mailId());
            log.info("Mail {} {} sent", mail.mailId(), mail.kind());
        } else if (reply instanceof Reply.Refused<Object> refused
            && refused.status() != HttpStatus.TOO_MANY_REQUESTS.value() && refused.status() < Reply.SERVER_ERROR) {
            mails.markFailed(mail.mailId());
            log.warn("Mail {} {} refused by the provider with HTTP {}", mail.mailId(), mail.kind(), refused.status());
        } else if (attempt >= props.mail().maxAttempts()) {
            mails.markFailed(mail.mailId());
            log.warn("Mail {} {} not sent after {} attempts: {}", mail.mailId(), mail.kind(), attempt, outcome(reply));
        } else {
            var pause = props.mail().firstRetry().multipliedBy(1L << (attempt - 1));
            mails.retryLater(mail.mailId(), pause);
            log.warn("Mail {} {} not sent, attempt {}: {}; retried in {}", mail.mailId(), mail.kind(), attempt, outcome(reply), pause);
        }
    }

    private static String outcome(Reply<?> reply) {
        return reply instanceof Reply.Refused<?> refused ? "HTTP " + refused.status() : reply.getClass().getSimpleName();
    }
}
