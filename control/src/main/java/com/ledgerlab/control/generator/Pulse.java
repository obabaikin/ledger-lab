package com.ledgerlab.control.generator;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;

@Schema(description = "A rise of the load above its base for a while.")
public record Pulse(int tps, Instant until) {

    public Pulse {
        Objects.requireNonNull(until, "until");
    }
}
