package com.ledgerlab.control.entry;

import com.ledgerlab.control.ControlProperties;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Deletes under the requests' lock: a request in between would lose its address, or fail on it with the address in the log. */
@Component
public class AddressCleanup {

    private static final Logger log = LoggerFactory.getLogger(AddressCleanup.class);

    private final InviteMailRepository mails;
    private final ControlProperties props;
    private final TransactionTemplate tx;

    public AddressCleanup(InviteMailRepository mails, ControlProperties props, TransactionTemplate tx) {
        this.mails = mails;
        this.props = props;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${control.cleanup.interval}", initialDelayString = "${control.cleanup.interval}")
    public int deleteUnused() {
        var deleted = locked(() -> mails.deleteUnusedSince(mails.now().minus(props.invites().email().retention())));
        if (deleted > 0) {
            log.info("Deleted {} addresses unused for {}", deleted, props.invites().email().retention());
        }
        return deleted;
    }

    @Scheduled(fixedDelayString = "${control.invites.email.deletion-check}", initialDelayString = "${control.invites.email.deletion-check}")
    public int deleteRequested() {
        var deleted = locked(mails::deleteDue);
        if (deleted > 0) {
            log.info("Deleted {} addresses on request", deleted);
        }
        return deleted;
    }

    private int locked(IntSupplier delete) {
        return Objects.requireNonNull(
            tx.execute(status -> {
                mails.lock();
                return delete.getAsInt();
            }),
            "the number of deleted addresses");
    }
}
