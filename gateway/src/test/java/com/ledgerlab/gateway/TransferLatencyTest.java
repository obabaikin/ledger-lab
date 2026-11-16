package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.gateway.service.TransferLatency;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransferLatencyTest {

    private static final Duration THREE = Duration.ofSeconds(3);
    private static final Duration FIVE = Duration.ofSeconds(5);

    private static List<Double> countAt(Timer timer, Duration bound) {
        return Arrays.stream(timer.takeSnapshot().histogramCounts())
            .filter(it -> it.bucket(TimeUnit.MILLISECONDS) == bound.toMillis())
            .mapToDouble(CountAtBucket::count)
            .boxed()
            .toList();
    }

    @Test
    @DisplayName("a transfer of exactly 3.0 s counts within 3 s, one a millisecond slower within 5 s only, one above 5 s in neither")
    void a_transfer_of_exactly_3_s_counts_within_3_s_one_a_millisecond_slower_within_5_s_only_one_above_5_s_in_neither() {
        var timer = TransferLatency.timer(new SimpleMeterRegistry(), List.of(THREE, FIVE));

        timer.record(THREE);
        timer.record(THREE.plusMillis(1));
        timer.record(FIVE.plusMillis(1));

        assertThat(countAt(timer, THREE)).containsExactly(1.0);
        assertThat(countAt(timer, FIVE)).containsExactly(2.0);
        assertThat(timer.count()).isEqualTo(3);
    }
}
