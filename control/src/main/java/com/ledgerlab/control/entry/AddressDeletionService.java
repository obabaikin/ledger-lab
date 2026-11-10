package com.ledgerlab.control.entry;

import com.ledgerlab.control.ControlProperties;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AddressDeletionService {

    private final InviteMailRepository mails;
    private final ControlProperties props;
    private final TransactionTemplate tx;

    public AddressDeletionService(InviteMailRepository mails, ControlProperties props, TransactionTemplate tx) {
        this.mails = mails;
        this.props = props;
        this.tx = tx;
    }

    public Deletion request(@Nullable String token) {
        if (token == null || token.isBlank()) {
            throw new TokenRequiredException();
        }
        var deletion = tx.execute(status -> {
            mails.lock();
            return Optional.ofNullable(mails.requestDeletion(token, mails.now().plus(props.invites().email().window())));
        });
        if (deletion == null || deletion.isEmpty()) {
            throw new UnknownLinkException();
        }
        return deletion.get();
    }
}
