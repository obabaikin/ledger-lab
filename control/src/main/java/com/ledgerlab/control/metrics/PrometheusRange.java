package com.ledgerlab.control.metrics;

import java.util.List;
import java.util.Objects;

/** Each of {@link #values} is a Unix time in seconds and the value as text, as Prometheus sends them. */
public record PrometheusRange(List<List<Object>> values) {

    public PrometheusRange {
        values = List.copyOf(Objects.requireNonNull(values, "values"));
    }
}
