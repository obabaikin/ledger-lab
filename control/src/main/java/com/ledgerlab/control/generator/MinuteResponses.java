package com.ledgerlab.control.generator;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** {@code baseTps} is null from a generator older than the base load's profile. */
public record MinuteResponses(Instant minute, @Nullable Integer baseTps, long total, long errors, long busy) {

    public MinuteResponses {
        Objects.requireNonNull(minute, "minute");
    }
}
