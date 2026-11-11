package com.ledgerlab.control.periods;

import java.time.Duration;
import java.time.Instant;

/** Periods lie on a grid from the epoch, so control cuts time at the same instants as the bank's services. */
public record Period(Instant start, Instant end) {

    public static Period containing(Instant instant, Duration length) {
        var seconds = length.toSeconds();
        var start = Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), seconds) * seconds);
        return new Period(start, start.plus(length));
    }

    public static boolean startsOnGrid(Instant start, Duration length) {
        return containing(start, length).start().equals(start);
    }
}
