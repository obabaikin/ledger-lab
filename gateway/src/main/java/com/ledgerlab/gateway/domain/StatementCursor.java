package com.ledgerlab.gateway.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** The last line of a page: the statement's order is its key, so the next page starts right after it. */
public record StatementCursor(Instant acceptedAt, UUID transferId) {

    private static final String SEPARATOR = "_";

    public static StatementCursor after(StatementLine line) {
        return new StatementCursor(line.acceptedAt(), line.transferId());
    }

    /** Fails with IllegalArgumentException for a text that is not a cursor this gateway gave out. */
    public static StatementCursor parse(String text) {
        var parts = text.split(SEPARATOR, -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Not a statement cursor: " + text);
        }
        return new StatementCursor(Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS), UUID.fromString(parts[1]));
    }

    public String text() {
        return ChronoUnit.MICROS.between(Instant.EPOCH, acceptedAt) + SEPARATOR + transferId;
    }
}
