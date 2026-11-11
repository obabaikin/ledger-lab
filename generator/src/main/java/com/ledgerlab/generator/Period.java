package com.ledgerlab.generator;

import java.time.Duration;
import java.time.Instant;

/** Periods lie on a grid from the epoch, so the generator cuts time at the same instants as the bank. */
public record Period(Instant start, Instant end) {

    public static Period containing(Instant instant, Duration length) {
        var seconds = length.toSeconds();
        var start = Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), seconds) * seconds);
        return new Period(start, start.plus(length));
    }

    public static boolean startsOnGrid(Instant start, Duration length) {
        return containing(start, length).start().equals(start);
    }

    public Period next() {
        return new Period(end, end.plus(Duration.between(start, end)));
    }

    public Period previous() {
        return new Period(start.minus(Duration.between(start, end)), start);
    }
}
