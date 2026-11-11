package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.reporting.domain.Period;
import com.ledgerlab.reporting.persistence.PartitionRepository;
import com.ledgerlab.reporting.service.Partitions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

// The schedule would create and drop partitions between the steps of a test.
@TestPropertySource(properties = "reporting.periods.maintenance-interval=1h")
class PartitionsTest extends IntegrationTest {

    @Autowired Partitions partitions;
    @Autowired PartitionRepository partitionRepository;
    @Autowired MeterRegistry registry;

    private final Instant now = Instant.now();

    @BeforeEach
    void partitionsAsOfTheTestsNow() {
        partitions.maintain(now);
    }

    /** The other tests of this context find the partitions a service started now would have. */
    @AfterEach
    void partitionsAsOfNow() {
        var newest = periodOf(Instant.now().plus(props.periods().partitionsAhead())).start();
        jdbcTemplate.update("DELETE FROM " + PartitionRepository.DEFAULT_PARTITION);
        for (var start : partitionRepository.periodStarts()) {
            if (start.isAfter(newest)) {
                tx.executeWithoutResult(status -> partitionRepository.drop(start, props.periods().lockTimeout()));
            }
        }
        partitions.maintain(Instant.now());
    }

    private Period periodOf(Instant instant) {
        return Period.containing(instant, props.periods().length());
    }

    private double gauge(String metric) {
        return registry.get(metric).gauge().value();
    }

    private double failures() {
        return registry.get(Partitions.FAILURES_METRIC).counter().count();
    }

    @Test
    void a_started_service_has_a_partition_for_every_period_from_the_oldest_it_holds_to_the_time_ahead() {
        var starts = partitionRepository.periodStarts();

        var oldest = periodOf(now.minus(props.periods().retention())).start();
        var newest = periodOf(now.plus(props.periods().partitionsAhead())).start();
        assertThat(starts).first().isEqualTo(oldest);
        assertThat(starts).last().isEqualTo(newest);
        assertThat(starts).hasSize((int) (Duration.between(oldest, newest).dividedBy(props.periods().length()) + 1));
        var periodsAhead = (int) props.periods().partitionsAhead().dividedBy(props.periods().length());
        assertThat((int) gauge(Partitions.AHEAD_METRIC)).isBetween(periodsAhead - 1, periodsAhead);
    }

    @Test
    void maintenance_at_the_same_time_changes_nothing() {
        var before = partitionRepository.periodStarts();
        var failuresBefore = failures();

        partitions.maintain(now);

        assertThat(partitionRepository.periodStarts()).isEqualTo(before);
        assertThat(failures()).isEqualTo(failuresBefore);
    }

    @Test
    void a_partition_is_dropped_once_its_whole_period_is_older_than_the_retention_and_the_next_one_stays() {
        var oldest = periodOf(now.minus(props.periods().retention()));
        assertThat(partitionRepository.periodStarts()).contains(oldest.start(), oldest.end());

        partitions.maintain(oldest.end().plus(props.periods().retention()).minusMillis(1));
        assertThat(partitionRepository.periodStarts()).contains(oldest.start());

        partitions.maintain(oldest.end().plus(props.periods().retention()));
        assertThat(partitionRepository.periodStarts()).doesNotContain(oldest.start()).contains(oldest.end());
    }

    @Test
    void the_numbers_of_a_period_go_with_its_partition_and_the_next_period_keeps_its_own() {
        var oldest = periodOf(now.minus(props.periods().retention()));
        store(command(100, oldest.end().minusSeconds(1)), command(100, oldest.end()));
        assertThat(seconds.sum(oldest).accepted()).isEqualTo(1);

        partitions.maintain(oldest.end().plus(props.periods().retention()));

        assertThat(seconds.sum(oldest).accepted()).isZero();
        assertThat(seconds.sum(oldest.next()).accepted()).isEqualTo(1);
    }

    @Test
    void with_no_partition_ahead_a_transfer_is_kept_in_the_default_partition_and_the_metrics_show_it() {
        var beyondEveryPartition = now.plus(props.periods().partitionsAhead()).plus(Duration.ofDays(30));

        var transfer = command(100, beyondEveryPartition);
        store(transfer);
        partitions.measure(beyondEveryPartition);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + PartitionRepository.DEFAULT_PARTITION + " WHERE transfer_id = ?", Long.class, transfer.transferId()))
            .isEqualTo(1);
        assertThat(seconds.sum(periodOf(beyondEveryPartition)).accepted()).isEqualTo(1);
        assertThat(gauge(Partitions.AHEAD_METRIC)).isZero();
        assertThat(gauge(Partitions.UNPARTITIONED_ROWS_METRIC)).isEqualTo(1);
    }

    @Test
    void a_partition_that_cannot_be_created_is_counted_and_the_ones_after_it_are_created() {
        var beyond = periodOf(now.plus(props.periods().partitionsAhead()).plus(Duration.ofDays(30)));
        // A row in the default partition stands in the way of the partition for its period.
        store(command(100, beyond.start().plusSeconds(1)));
        var failuresBefore = failures();

        partitions.maintain(beyond.start().minus(props.periods().partitionsAhead()).plus(props.periods().length()));

        assertThat(partitionRepository.periodStarts()).doesNotContain(beyond.start()).contains(beyond.end());
        assertThat(failures() - failuresBefore).isEqualTo(1);
    }
}
