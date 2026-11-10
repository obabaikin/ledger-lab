package com.ledgerlab.control.generator;

import java.util.Objects;

public record UnknownResolved(TransferIds accepted, TransferIds notAccepted) {
    public UnknownResolved {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(notAccepted, "notAccepted");
    }
}
