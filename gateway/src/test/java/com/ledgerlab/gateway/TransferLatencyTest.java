package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.gateway.service.TransferLatency;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransferLatencyTest {

    private static final Duration THRESHOLD = Duration.ofSeconds(2);

    @Test
    @DisplayName("a transfer of exactly the threshold counts in the threshold's bucket, one a millisecond slower does not")
    void a_transfer_of_exactly_the_threshold_counts_in_the_thresholds_bucket_one_a_millisecond_slower_does_not() {
        var timer = TransferLatency.timer(new SimpleMeterRegistry(), THRESHOLD);

        timer.record(THRESHOLD);
        timer.record(THRESHOLD.plusMillis(1));

        var atThreshold = Arrays.stream(timer.takeSnapshot().histogramCounts())
            .filter(it -> it.bucket(TimeUnit.SECONDS) == THRESHOLD.toSeconds())
            .mapToDouble(CountAtBucket::count)
            .boxed()
            .toList();
        assertThat(atThreshold).containsExactly(1.0);
        assertThat(timer.count()).isEqualTo(2);
    }
}
