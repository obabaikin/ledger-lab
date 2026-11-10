package com.ledgerlab.gateway.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A transfer's state at the bank; POSTED and REJECTED are final.")
public enum TransferStatus {
    ACCEPTED,
    PUBLISHED,
    POSTED,
    REJECTED;

    public boolean isFinal() {
        return this == POSTED || this == REJECTED;
    }
}
