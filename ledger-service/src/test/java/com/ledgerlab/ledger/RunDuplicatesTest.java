package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ledgerlab.ledger.metrics.RunDuplicates;
import com.ledgerlab.ledger.metrics.RunDuplicatesProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunDuplicatesTest {

    private static Double duplicates(MeterRegistry registry, UUID runId) {
        var counter = registry.find(RunDuplicates.METRIC).tag(RunDuplicates.RUN_TAG, runId.toString()).counter();
        return counter == null ? null : counter.count();
    }

    @Test
    void duplicates_are_counted_per_run() {
        var registry = new SimpleMeterRegistry();
        var duplicates = new RunDuplicates(registry, new RunDuplicatesProperties(Duration.ofMinutes(10)));
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();

        for (var i = 0; i < 3; i++) {
            duplicates.record(a);
        }
        duplicates.record(b);

        assertThat(duplicates(registry, a)).isEqualTo(3.0);
        assertThat(duplicates(registry, b)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an idle run's counter is removed and a later duplicate starts a new one")
    void an_idle_runs_counter_is_removed_and_a_later_duplicate_starts_a_new_one() {
        var registry = new SimpleMeterRegistry();
        var duplicates = new RunDuplicates(registry, new RunDuplicatesProperties(Duration.ZERO));
        var runId = UUID.randomUUID();

        duplicates.record(runId);
        duplicates.removeIdle();

        assertThat(duplicates(registry, runId)).isNull();
        duplicates.record(runId);
        assertThat(duplicates(registry, runId)).isEqualTo(1.0);
    }

    @Test
    void a_failing_registry_does_not_fail_the_transfer_that_was_a_duplicate() {
        var broken = new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("registry closed");
            }
        };
        var duplicates = new RunDuplicates(broken, new RunDuplicatesProperties(Duration.ofMinutes(10)));

        assertThatCode(() -> duplicates.record(UUID.randomUUID())).doesNotThrowAnyException();
    }
}
