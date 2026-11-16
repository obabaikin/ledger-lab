package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.metrics.BankMetrics;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BankMetricsTest {

    @Test
    @DisplayName("a bucket bound is written as Prometheus keeps the gateway's: 2 s as 2.0, 500 ms as 0.5")
    void a_bucket_bound_is_written_as_Prometheus_keeps_the_gateways_2_s_as_2_0_500_ms_as_0_5() {
        assertThat(BankMetrics.bucketBound(Duration.ofSeconds(2))).isEqualTo("2.0");
        assertThat(BankMetrics.bucketBound(Duration.ofMillis(500))).isEqualTo("0.5");
        assertThat(BankMetrics.WITHIN_IN_MINUTE.formatted(BankMetrics.bucketBound(Duration.ofSeconds(3))))
            .isEqualTo("sum(increase(gateway_transfer_latency_seconds_bucket{le=\"3.0\"}[1m]))");
    }

    @Test
    @DisplayName("the transfers of a minute are counted only on the instances with a bucket at each bound")
    void the_transfers_of_a_minute_are_counted_only_on_the_instances_with_a_bucket_at_each_bound() {
        assertThat(BankMetrics.transfersInMinute(List.of(Duration.ofSeconds(3), Duration.ofSeconds(5)))).isEqualTo(
            "sum(increase(gateway_transfer_latency_seconds_count[1m])"
                + " and on(instance) gateway_transfer_latency_seconds_bucket{le=\"3.0\"}"
                + " and on(instance) gateway_transfer_latency_seconds_bucket{le=\"5.0\"})");
    }

    @Test
    @DisplayName("a window is written in whole minutes where they fit, else in seconds")
    void a_window_is_written_in_whole_minutes_where_they_fit_else_in_seconds() {
        assertThat(BankMetrics.window(Duration.ofSeconds(15))).isEqualTo("15s");
        assertThat(BankMetrics.window(Duration.ofSeconds(90))).isEqualTo("90s");
        assertThat(BankMetrics.window(Duration.ofMinutes(1))).isEqualTo("1m");
    }
}
