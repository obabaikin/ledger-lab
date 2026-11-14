package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LatencyHistogramTest extends IntegrationTest {

    @Test
    @DisplayName("the gateway publishes its time from acceptance to result as a histogram with a bucket at the threshold")
    void the_gateway_publishes_its_time_from_acceptance_to_result_as_a_histogram_with_a_bucket_at_the_threshold() {
        var metrics = http.getForEntity("/actuator/prometheus", String.class).getBody();

        assertThat(metrics).contains("gateway_transfer_latency_seconds_bucket{le=\"2.0\"}");
        assertThat(metrics).doesNotContain("gateway_transfer_latency_seconds{quantile=");
    }
}
