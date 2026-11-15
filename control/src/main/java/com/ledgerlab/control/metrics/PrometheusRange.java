package com.ledgerlab.control.metrics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Each of {@link #values} is a Unix time in seconds and the value as text, as Prometheus sends them; {@link #metric} its labels. */
public record PrometheusRange(Map<String, String> metric, List<List<Object>> values) {

    public PrometheusRange(@Nullable Map<String, String> metric, List<List<Object>> values) {
        this.metric = metric == null ? Map.of() : Map.copyOf(metric);
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
    }
}
