package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.ledger.domain.Period;
import com.ledgerlab.ledger.persistence.PartitionRepository;
import com.ledgerlab.ledger.persistence.PartitionedTable;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.Partitions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// The schedule would create and drop partitions between the steps of a test.
@TestPropertySource(properties = "ledger.periods.maintenance-interval=1h")
class PartitionsTest extends IntegrationTest {

    @Autowired Partitions partitions;
    @Autowired PartitionRepository partitionRepository;
    @Autowired LedgerService ledger;
    @Autowired MeterRegistry registry;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    private final Instant now = Instant.now();

    @BeforeEach
    void partitionsAsOfTheTestsNow() {
        partitions.maintain(now);
    }

    /** The other tests of this context find the partitions a ledger started now would have. */
    @AfterEach
    void partitionsAsOfNow() {
        var newest = periodOf(Instant.now().plus(periodsProperties.partitionsAhead())).start();
        for (var table : PartitionedTable.values()) {
            jdbc.sql("DELETE FROM " + PartitionRepository.defaultPartition(table)).update();
            for (var start : partitionRepository.periodStarts(table)) {
                if (start.isAfter(newest)) {
                    tx.executeWithoutResult(status -> partitionRepository.drop(table, start, periodsProperties.lockTimeout()));
                }
            }
        }
        partitions.maintain(Instant.now());
    }

    private Period periodOf(Instant instant) {
        return Period.containing(instant, periodsProperties.length());
    }

    private double gauge(String metric, PartitionedTable table) {
        return registry.get(metric).tag(Partitions.TABLE_TAG, table.tableName()).gauge().value();
    }

    @ParameterizedTest
    @EnumSource(PartitionedTable.class)
    void a_started_ledger_has_a_partition_for_every_period_from_the_oldest_it_holds_to_the_time_ahead(PartitionedTable table) {
        var starts = partitionRepository.periodStarts(table);

        var oldest = periodOf(now.minus(periodsProperties.retention())).start();
        var newest = periodOf(now.plus(periodsProperties.partitionsAhead())).start();
        assertThat(starts).first().isEqualTo(oldest);
        assertThat(starts).last().isEqualTo(newest);
        assertThat(starts).hasSize((int) (Duration.between(oldest, newest).dividedBy(periodsProperties.length()) + 1));
        var periodsAhead = (int) periodsProperties.partitionsAhead().dividedBy(periodsProperties.length());
        assertThat((int) gauge(Partitions.AHEAD_METRIC, table)).isBetween(periodsAhead - 1, periodsAhead);
    }

    @Test
    void maintenance_at_the_same_time_changes_nothing() {
        var before = partitionRepository.periodStarts(PartitionedTable.TRANSFERS);
        var failuresBefore = registry.get(Partitions.FAILURES_METRIC).counter().count();

        partitions.maintain(now);

        assertThat(partitionRepository.periodStarts(PartitionedTable.TRANSFERS)).isEqualTo(before);
        assertThat(registry.get(Partitions.FAILURES_METRIC).counter().count()).isEqualTo(failuresBefore);
    }

    @ParameterizedTest
    @EnumSource(PartitionedTable.class)
    void a_partition_is_dropped_once_its_whole_period_is_older_than_the_retention_and_the_next_one_stays(PartitionedTable table) {
        var oldest = periodOf(now.minus(periodsProperties.retention()));
        assertThat(partitionRepository.periodStarts(table)).contains(oldest.start(), oldest.end());

        partitions.maintain(oldest.end().plus(periodsProperties.retention()).minusMillis(1));
        assertThat(partitionRepository.periodStarts(table)).contains(oldest.start());

        partitions.maintain(oldest.end().plus(periodsProperties.retention()));
        assertThat(partitionRepository.periodStarts(table)).doesNotContain(oldest.start()).contains(oldest.end());
    }

    @Test
    void with_no_partition_ahead_a_transfer_is_kept_in_the_default_partition_and_the_metrics_show_it() {
        openBank(2, 1_000);
        var beyondEveryPartition = now.plus(periodsProperties.partitionsAhead()).plus(Duration.ofDays(30));

        assertThat(applyOne(ledger, transferAt(beyondEveryPartition, 1, 2, 100))).isEqualTo(POSTED);
        partitions.measure(beyondEveryPartition);

        assertThat(jdbc.sql("SELECT count(*) FROM transfers_default").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entries_default").query(Long.class).single()).isEqualTo(2);
        assertThat(gauge(Partitions.AHEAD_METRIC, PartitionedTable.TRANSFERS)).isZero();
        assertThat(gauge(Partitions.UNPARTITIONED_ROWS_METRIC, PartitionedTable.TRANSFERS)).isEqualTo(1);
        assertThat(gauge(Partitions.UNPARTITIONED_ROWS_METRIC, PartitionedTable.JOURNAL_ENTRIES)).isEqualTo(2);
        awaitCreditsApplied();
        assertThat(accountRepository.books().balanced()).isTrue();
    }

    @Test
    void a_partition_that_cannot_be_created_is_counted_and_the_ones_after_it_are_created() {
        openBank(2, 1_000);
        var beyond = periodOf(now.plus(periodsProperties.partitionsAhead()).plus(Duration.ofDays(30)));
        // A row in the default partition stands in the way of the partition for its period.
        applyOne(ledger, transferAt(beyond.start().plusSeconds(1), 1, 2, 100));
        var failuresBefore = registry.get(Partitions.FAILURES_METRIC).counter().count();

        partitions.maintain(beyond.start().minus(periodsProperties.partitionsAhead()).plus(periodsProperties.length()));

        assertThat(partitionRepository.periodStarts(PartitionedTable.TRANSFERS)).doesNotContain(beyond.start()).contains(beyond.end());
        assertThat(registry.get(Partitions.FAILURES_METRIC).counter().count() - failuresBefore).isEqualTo(2);
    }
}
