package com.ledgerlab.control.metrics;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** {@link #value} is a Unix time in seconds and the value as text, as Prometheus sends them. */
public record PrometheusSample(Map<String, String> metric, List<Object> value) {
    public PrometheusSample {
        Objects.requireNonNull(metric, "metric");
        value = List.copyOf(Objects.requireNonNull(value, "value"));
    }

    public double number() {
        return Double.parseDouble(value.get(1).toString());
    }
}
