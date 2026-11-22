package com.ledgerlab.generator.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.generator.GeneratorProperties;
import com.ledgerlab.generator.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// The schedule would create and drop partitions between the steps of a test.
@TestPropertySource(properties = "generator.periods.maintenance-interval=1h")
class PartitionsTest extends IntegrationTest {

    @Autowired Partitions partitions;
    @Autowired PartitionRepository partitionRepository;
    @Autowired MeterRegistry registry;
    @Autowired TransactionTemplate tx;

    private final Instant now = Instant.now();

    @BeforeEach
    void partitionsAsOfTheTestsNow() {
        partitions.maintain(now);
    }

    /** The other tests of this context find the partitions a generator started now would have. */
    @AfterEach
    void partitionsAsOfNow() {
        var newest = periodOf(Instant.now().plus(props.periods().partitionsAhead())).start();
        for (var table : PartitionedTable.values()) {
            jdbcTemplate.update("DELETE FROM " + PartitionRepository.defaultPartition(table));
            for (var start : partitionRepository.periodStarts(table)) {
                if (start.isAfter(newest)) {
                    tx.executeWithoutResult(status -> partitionRepository.drop(table, start, props.periods().lockTimeout()));
                }
            }
        }
        partitions.maintain(Instant.now());
    }

    private Period periodOf(Instant instant) {
        return Period.containing(instant, props.periods().length());
    }

    private double gauge(String metric, PartitionedTable table) {
        return registry.get(metric).tag(Partitions.TABLE_TAG, table.tableName()).gauge().value();
    }

    private double failures() {
        return registry.get(Partitions.FAILURES_METRIC).counter().count();
    }

    @ParameterizedTest
    @EnumSource(PartitionedTable.class)
    void a_started_generator_has_a_partition_for_every_period_from_the_oldest_it_holds_to_the_time_ahead(PartitionedTable table) {
        var starts = partitionRepository.periodStarts(table);

        var oldest = periodOf(now.minus(props.periods().retention())).start();
        var newest = periodOf(now.plus(props.periods().partitionsAhead())).start();
        assertThat(starts).first().isEqualTo(oldest);
        assertThat(starts).last().isEqualTo(newest);
        assertThat(starts).hasSize((int) (Duration.between(oldest, newest).dividedBy(props.periods().length()) + 1));
        var periodsAhead = (int) props.periods().partitionsAhead().dividedBy(props.periods().length());
        assertThat((int) gauge(Partitions.AHEAD_METRIC, table)).isBetween(periodsAhead - 1, periodsAhead);
    }

    @Test
    void a_generator_started_with_a_shorter_period_length_than_its_partitions_refuses_to_start_and_drops_none_of_them() {
        var before = partitionRepository.periodStarts(PartitionedTable.INTENTS);
        var p = props;
        var q = p.periods();
        var shorter = new GeneratorProperties(p.gatewayUrl(), p.connectTimeout(), p.requestTimeout(), p.maxAttempts(), p.retryBackoff(),
            p.maxInFlight(), p.load(), p.accounts(), p.journal(),
            new GeneratorProperties.Periods(Duration.ofMinutes(1), q.retention(), q.partitionsAhead(), q.maintenanceInterval(), q.lockTimeout(),
                q.unpartitionedDeleteBatch()),
            p.reconciliation(), p.totals(), p.lease(), p.profile());
        var restarted = new Partitions(partitionRepository, shorter, Clock.fixed(now, ZoneOffset.UTC), tx, new SimpleMeterRegistry());

        assertThatThrownBy(restarted::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(PartitionedTable.INTENTS.tableName() + "_p");
        assertThat(partitionRepository.periodStarts(PartitionedTable.INTENTS)).isEqualTo(before);
    }

    @Test
    void maintenance_at_the_same_time_changes_nothing() {
        var before = partitionRepository.periodStarts(PartitionedTable.INTENTS);
        var failuresBefore = failures();

        partitions.maintain(now);

        assertThat(partitionRepository.periodStarts(PartitionedTable.INTENTS)).isEqualTo(before);
        assertThat(failures()).isEqualTo(failuresBefore);
    }

    @ParameterizedTest
    @EnumSource(PartitionedTable.class)
    void a_partition_is_dropped_once_its_whole_period_is_older_than_the_retention_and_the_next_one_stays(PartitionedTable table) {
        var oldest = periodOf(now.minus(props.periods().retention()));
        assertThat(partitionRepository.periodStarts(table)).contains(oldest.start(), oldest.end());

        partitions.maintain(oldest.end().plus(props.periods().retention()).minusMillis(1));
        assertThat(partitionRepository.periodStarts(table)).contains(oldest.start());

        partitions.maintain(oldest.end().plus(props.periods().retention()));
        assertThat(partitionRepository.periodStarts(table)).doesNotContain(oldest.start()).contains(oldest.end());
    }

    @Test
    void the_journal_of_a_period_goes_with_its_partitions_and_the_next_periods_stays() {
        var oldest = periodOf(now.minus(props.periods().retention()));
        var dropped = agreed(oldest.start().plusSeconds(1));
        var kept = agreed(oldest.end().plusSeconds(1));

        partitions.maintain(oldest.end().plus(props.periods().retention()));

        assertThat(jdbcTemplate.queryForList("SELECT transfer_id FROM intents", java.util.UUID.class)).containsExactly(kept);
        assertThat(outcomeRows(dropped)).isZero();
        assertThat(outcomeRows(kept)).isEqualTo(1);
    }

    @Test
    void with_no_partition_ahead_the_journal_is_kept_in_the_default_partitions_and_the_metrics_show_it() {
        var beyondEveryPartition = now.plus(props.periods().partitionsAhead()).plus(Duration.ofDays(30));

        var transferId = agreed(beyondEveryPartition);
        partitions.measure(beyondEveryPartition);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intents_default WHERE transfer_id = ?", Long.class, transferId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outcomes_default WHERE transfer_id = ?", Long.class, transferId)).isEqualTo(1);
        for (var table : PartitionedTable.values()) {
            assertThat(gauge(Partitions.AHEAD_METRIC, table)).isZero();
            assertThat(gauge(Partitions.UNPARTITIONED_ROWS_METRIC, table)).isEqualTo(1);
        }
    }

    @Test
    void a_partition_that_cannot_be_created_is_counted_and_the_ones_after_it_are_created() {
        var beyond = periodOf(now.plus(props.periods().partitionsAhead()).plus(Duration.ofDays(30)));
        // A row in the default partition stands in the way of the partition for its period.
        intent(beyond.start().plusSeconds(1));
        var failuresBefore = failures();

        partitions.maintain(beyond.start().minus(props.periods().partitionsAhead()).plus(props.periods().length()));

        assertThat(partitionRepository.periodStarts(PartitionedTable.INTENTS)).doesNotContain(beyond.start()).contains(beyond.end());
        assertThat(partitionRepository.periodStarts(PartitionedTable.OUTCOMES)).contains(beyond.start(), beyond.end());
        assertThat(failures() - failuresBefore).isEqualTo(1);
    }

    @Test
    void the_journal_of_a_period_whose_partitions_were_dropped_by_hand_stays_outside_the_partitions_only_as_long_as_the_period_is_held() {
        var current = periodOf(now);
        for (var table : PartitionedTable.values()) {
            tx.executeWithoutResult(status -> partitionRepository.drop(table, current.start(), props.periods().lockTimeout()));
        }
        var rows = props.periods().unpartitionedDeleteBatch() + 1;
        for (var i = 0; i < rows; i++) {
            agreed(now.plusMillis(i));
        }

        partitions.maintain(now);
        for (var table : PartitionedTable.values()) {
            assertThat(gauge(Partitions.UNPARTITIONED_ROWS_METRIC, table)).isEqualTo(rows);
        }

        partitions.maintain(current.end().plus(props.periods().retention()).minusMillis(1));
        for (var table : PartitionedTable.values()) {
            assertThat(gauge(Partitions.UNPARTITIONED_ROWS_METRIC, table)).isEqualTo(rows);
        }

        partitions.maintain(current.end().plus(props.periods().retention()));
        for (var table : PartitionedTable.values()) {
            assertThat(gauge(Partitions.UNPARTITIONED_ROWS_METRIC, table)).isZero();
        }
    }
}
