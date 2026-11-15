package com.ledgerlab.control.generator;

import java.time.Instant;
import java.util.Objects;

public record MinuteResponses(Instant minute, long total, long errors, long busy) {

    public MinuteResponses {
        Objects.requireNonNull(minute, "minute");
    }
}
