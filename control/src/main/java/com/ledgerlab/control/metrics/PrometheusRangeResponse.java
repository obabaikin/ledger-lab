package com.ledgerlab.control.metrics;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record PrometheusRangeResponse(String status, @Nullable Data data) {

    public PrometheusRangeResponse {
        Objects.requireNonNull(status, "status");
    }

    public record Data(List<PrometheusRange> result) {

        public Data {
            result = List.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}
