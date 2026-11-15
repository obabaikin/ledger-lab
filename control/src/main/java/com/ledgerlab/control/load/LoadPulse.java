package com.ledgerlab.control.load;

import java.time.Instant;
import java.util.Objects;

/** A visitor's pulse, timed by control's database clock. */
public record LoadPulse(Instant at, int tps, int seconds, Instant until) {

    public LoadPulse {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(until, "until");
    }
}
