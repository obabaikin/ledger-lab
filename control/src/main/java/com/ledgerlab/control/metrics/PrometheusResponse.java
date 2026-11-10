package com.ledgerlab.control.metrics;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record PrometheusResponse(String status, @Nullable PrometheusData data) {
    public PrometheusResponse {
        Objects.requireNonNull(status, "status");
    }
}
