package com.ledgerlab.control.metrics;

import java.util.List;
import java.util.Objects;

public record PrometheusData(List<PrometheusSample> result) {
    public PrometheusData {
        result = List.copyOf(Objects.requireNonNull(result, "result"));
    }
}
