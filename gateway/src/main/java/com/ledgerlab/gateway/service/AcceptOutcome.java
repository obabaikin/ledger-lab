package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.domain.Transfer;

public sealed interface AcceptOutcome {

    record Accepted(Transfer transfer) implements AcceptOutcome {
    }

    record Refused(RuntimeException cause) implements AcceptOutcome {
    }
}
