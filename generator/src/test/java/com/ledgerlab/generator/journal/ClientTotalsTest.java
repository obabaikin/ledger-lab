package com.ledgerlab.generator.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.generator.IntegrationTest;
import com.ledgerlab.generator.TestData;
import com.ledgerlab.generator.bank.TransferStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ClientTotalsTest extends IntegrationTest {

    private static final int ROUNDS = 20;
    private static final int DELTAS_PER_ROUND = 200;

    @Autowired JournalWriter writer;
    @Autowired ClientTotalsStore store;
    @Autowired JdbcClient jdbcClient;

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private long deltaRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM client_totals_delta", Long.class);
    }

    private long intentRows(UUID transferId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM intents WHERE transfer_id = ?", Long.class, transferId);
    }

    /** The writer takes rows in order: once a new intent is stored, every outcome handed over before it was written. */
    private void flushed() throws InterruptedException {
        writer.intent(now(), TestData.transfer());
    }

    @Test
    void a_written_batch_adds_its_intents_and_outcomes_to_the_totals() throws Exception {
        var acknowledged = TestData.transfer();
        var failed = TestData.transfer();
        var sentAt = now();
        writer.intent(sentAt, acknowledged);
        writer.intent(sentAt, failed);
        writer.outcome(sentAt, acknowledged, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, sentAt);
        writer.outcome(sentAt, failed, JournalOutcome.FAILED, null, null, null);
        flushed();

        // three intents (the flush included), one taken, one given up, one without an outcome
        assertThat(store.read()).isEqualTo(new ClientTotals(3, 1, 1, 1));
    }

    @Test
    @DisplayName("a batch whose increment cannot be written leaves neither the journal nor the totals changed")
    void a_batch_whose_increment_cannot_be_written_leaves_neither_the_journal_nor_the_totals_changed() throws Exception {
        jdbcTemplate.execute("CREATE FUNCTION refuse_delta() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'refused'; END $$");
        jdbcTemplate.execute("CREATE TRIGGER refuse_delta BEFORE INSERT ON client_totals_delta FOR EACH ROW EXECUTE FUNCTION refuse_delta()");
        var transfer = TestData.transfer();
        try {
            assertThatThrownBy(() -> writer.intent(now(), transfer)).isInstanceOf(JournalUnavailableException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER refuse_delta ON client_totals_delta");
            jdbcTemplate.execute("DROP FUNCTION refuse_delta()");
        }

        assertThat(intentRows(transfer.transferId())).isZero();
        assertThat(store.read()).isEqualTo(new ClientTotals(0, 0, 0, 0));
    }

    @Test
    @DisplayName("an outcome written twice adds to the totals once")
    void an_outcome_written_twice_adds_to_the_totals_once() throws Exception {
        var transfer = TestData.transfer();
        var sentAt = now();
        writer.intent(sentAt, transfer);
        writer.outcome(sentAt, transfer, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, sentAt);
        flushed();

        writer.outcome(sentAt, transfer, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, sentAt);
        writer.writeNow(List.of(new JournalWriter.Outcome(sentAt, transfer.transferId(), JournalOutcome.UNKNOWN_ACCEPTED, null, null, null)));
        flushed();

        assertThat(store.read()).isEqualTo(new ClientTotals(3, 1, 0, 2));
    }

    @Test
    void an_unknown_transfer_resolved_by_the_reconciliation_moves_from_unanswered_to_given_up() throws Exception {
        var transfer = TestData.transfer();
        var sentAt = now();
        writer.intent(sentAt, transfer);
        assertThat(store.read()).isEqualTo(new ClientTotals(1, 0, 0, 1));

        writer.writeNow(List.of(new JournalWriter.Outcome(sentAt, transfer.transferId(), JournalOutcome.UNKNOWN_ACCEPTED, null, null, null)));

        assertThat(store.read()).isEqualTo(new ClientTotals(1, 0, 1, 0));
    }

    @Test
    void a_fold_keeps_the_totals_and_leaves_no_increments() throws Exception {
        var transfer = TestData.transfer();
        var sentAt = now();
        writer.intent(sentAt, transfer);
        writer.outcome(sentAt, transfer, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, sentAt);
        flushed();
        var before = store.read();
        assertThat(deltaRows()).isPositive();

        store.rollUp();

        assertThat(store.read()).isEqualTo(before);
        assertThat(deltaRows()).isZero();
    }

    @Test
    void two_folds_at_once_add_every_increment_exactly_once() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var round = 1; round <= ROUNDS; round++) {
                jdbcTemplate.update("INSERT INTO client_totals_delta SELECT 1, 0, 0, 0, 0 FROM generate_series(1, ?)", DELTAS_PER_ROUND);
                var first = executor.submit(() -> {
                    barrier.await();
                    store.rollUp();
                    return null;
                });
                var second = executor.submit(() -> {
                    barrier.await();
                    store.rollUp();
                    return null;
                });
                first.get();
                second.get();

                assertThat(store.read().sent()).isEqualTo((long) round * DELTAS_PER_ROUND);
            }
        }
    }

    @Test
    void the_totals_outlive_the_journal_rows_they_counted() throws Exception {
        var sentAt = now();
        writer.intent(sentAt, TestData.transfer());
        store.rollUp();
        writer.intent(sentAt, TestData.transfer());

        jdbcTemplate.update("DELETE FROM outcomes");
        jdbcTemplate.update("DELETE FROM intents");

        assertThat(store.read().sent()).isEqualTo(2);
    }

    @Test
    void the_totals_are_not_shown_before_the_first_read_or_after_reads_stop() {
        var clock = new SettableClock(Instant.parse("2026-11-13T10:00:00Z"));
        var fresh = new ClientTotalsStore(jdbcClient, clock, props);
        assertThat(fresh.current()).isNull();

        fresh.refresh();
        clock.now = clock.now.plus(props.totals().readInterval().multipliedBy(ClientTotalsStore.STALE_AFTER_READS));
        assertThat(fresh.current()).isEqualTo(new ClientTotals(0, 0, 0, 0));

        clock.now = clock.now.plusMillis(1);
        assertThat(fresh.current()).isNull();
    }

    private static final class SettableClock extends Clock {

        private volatile Instant now;

        SettableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
