package com.ledgerlab.generator.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.generator.FakeGateway;
import com.ledgerlab.generator.IntegrationTest;
import com.ledgerlab.generator.bank.TransferStatus;
import com.ledgerlab.generator.journal.JournalOutcome;
import com.ledgerlab.generator.journal.Period;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The reconciliation of a period of a hundred thousand transfers, with statistics gathered before the period's journal
 * was written: as on a server where autoanalyze has not yet reached the hour's partitions.
 */
class ReconciliationAtScaleTest extends IntegrationTest {

    private static final int TRANSFERS = 100_000;
    private static final int STATEMENT_PAGE = 10_000;
    private static final Duration RECONCILIATION_LIMIT = Duration.ofSeconds(30);

    @AfterEach
    void autoanalyzeTheJournalAgain() {
        journalPartitions("RESET (autovacuum_enabled)");
    }

    @Test
    void names_the_transfer_the_client_never_sent_among_a_hundred_thousand_on_statistics_without_the_period() {
        var period = lastEndedPeriod();
        journalPartitions("SET (autovacuum_enabled = off)");
        jdbcTemplate.execute("ANALYZE intents");
        jdbcTemplate.execute("ANALYZE outcomes");
        gateway.reset(FakeGateway.with().periodLength(props.periods().length()).statementPage(STATEMENT_PAGE));
        everyTransferAgreedOn(period);
        var unknownToTheClient = UUID.randomUUID();
        gateway.accept(unknownToTheClient, 3, 4, 250, TransferStatus.POSTED, null, period.start().plusSeconds(1));

        var report = reconcile(period);

        assertThat(report.statement().rows()).isEqualTo(TRANSFERS + 1);
        assertThat(report.breaks().notInJournal()).isEqualTo(new TransferIds(1, List.of(unknownToTheClient)));
    }

    private void everyTransferAgreedOn(Period period) {
        jdbcTemplate.update(
            """
            INSERT INTO intents (sent_at, transfer_id, from_account, to_account, amount_cents)
            SELECT CAST(? AS timestamptz) + n * interval '10 microseconds', gen_random_uuid(), 1, 2, 100 FROM generate_series(1, ?) n
            """,
            Timestamp.from(period.start()), TRANSFERS);
        jdbcTemplate.update(
            "INSERT INTO outcomes (sent_at, transfer_id, outcome, bank_status, accepted_at) SELECT sent_at, transfer_id, ?, ?, sent_at FROM intents",
            JournalOutcome.ACKNOWLEDGED.name(), TransferStatus.POSTED.name());
        jdbcTemplate.query("SELECT sent_at, transfer_id FROM intents", rs -> {
            gateway.accept(rs.getObject("transfer_id", UUID.class), 1, 2, 100, TransferStatus.POSTED, null, rs.getTimestamp("sent_at").toInstant());
        });
    }

    private ClientReconciliationReport reconcile(Period period) {
        var path = "/internal/periods/" + period.start() + "/reconciliation";
        assertThat(http.postForEntity(path, null, PeriodReconciliation.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var done = await().atMost(RECONCILIATION_LIMIT)
            .until(() -> http.getForObject(path, PeriodReconciliation.class), it -> it.state() != ReconciliationState.RUNNING);
        assertThat(done.failure()).isNull();
        return done.report();
    }

    private void journalPartitions(String change) {
        jdbcTemplate.query(
            "SELECT inhrelid::regclass::text FROM pg_inherits WHERE inhparent IN ('intents'::regclass, 'outcomes'::regclass)",
            rs -> {
                jdbcTemplate.execute("ALTER TABLE " + rs.getString(1) + " " + change);
            });
    }
}
