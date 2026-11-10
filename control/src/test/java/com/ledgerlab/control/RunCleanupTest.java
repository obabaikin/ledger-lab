package com.ledgerlab.control;

import static com.ledgerlab.control.http.ServiceHttp.forRun;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.reporting.ReportingApi;
import com.ledgerlab.control.runs.RunCleanup;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RunCleanupTest extends IntegrationTest {

    @Autowired RunCleanup cleanup;

    private UUID ended(String state, long hoursAgo) {
        var runId = UUID.fromString((String) start(sessionOf(invite())).getBody().get("runId"));
        jdbc.sql("UPDATE runs SET state = :state, finished_at = clock_timestamp() - CAST(:hours AS bigint) * interval '1 hour' WHERE run_id = :id")
            .param("state", state).param("hours", hoursAgo).param("id", runId).update();
        return runId;
    }

    private boolean purged(UUID runId) {
        return jdbc.sql("SELECT purged_at IS NOT NULL FROM runs WHERE run_id = :id").param("id", runId).query(Boolean.class).single();
    }

    @Test
    void runs_that_ended_a_retention_ago_lose_their_bank_data_and_keep_their_row() throws Exception {
        var reconciled = ended("RECONCILED", 25);
        var failed = ended("FAILED", 25);
        var recent = ended("RECONCILED", 23);
        var expired = ended("EXPIRED", 25);
        ledger.status = 204;
        gateway.status = 204;
        reporting.status = 204;

        assertThat(cleanup.purge()).isEqualTo(2);

        assertThat(Stream.of(reconciled, failed).map(this::purged).toList()).containsOnly(true);
        assertThat(Stream.of(recent, expired).map(this::purged).toList()).containsOnly(false);
        assertThat(ledger.callsTo("DELETE", forRun(LedgerApi.RUN_PATH, reconciled))).hasSize(1);
        assertThat(gateway.callsTo("DELETE", forRun(GatewayApi.RUN_PATH, reconciled))).hasSize(1);
        assertThat(reporting.callsTo("DELETE", forRun(ReportingApi.RUN_PATH, reconciled))).hasSize(1);
        assertThat(ledger.callsTo("DELETE", forRun(LedgerApi.RUN_PATH, expired))).isEmpty();
        assertThat(stateOf(reconciled)).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("chaos events older than the retention are deleted, newer ones kept")
    void chaos_events_older_than_the_retention_are_deleted_newer_ones_kept() throws Exception {
        jdbc.sql(
            """
            INSERT INTO chaos_events (at, instances) VALUES
                (clock_timestamp() - interval '25 hours', '{ledger-0}'),
                (clock_timestamp() - interval '23 hours', '{ledger-1}')
            """).update();

        cleanup.purge();

        assertThat(jdbc.sql("SELECT array_to_string(instances, ',') FROM chaos_events").query(String.class).list()).containsExactly("ledger-1");
    }

    @Test
    void a_run_the_bank_no_longer_knows_counts_as_purged() throws Exception {
        var runId = ended("FAILED", 25);
        ledger.status = 404;
        gateway.status = 404;
        reporting.status = 404;

        cleanup.purge();

        assertThat(purged(runId)).isTrue();
    }

    @Test
    @DisplayName("a delete that fails is repeated on the next round, and a purged run is not deleted again")
    void a_delete_that_fails_is_repeated_on_the_next_round_and_a_purged_run_is_not_deleted_again() throws Exception {
        var runId = ended("RECONCILED", 25);
        ledger.status = 204;
        gateway.status = 503;
        reporting.status = 204;

        cleanup.purge();
        assertThat(purged(runId)).isFalse();

        gateway.status = 204;
        cleanup.purge();
        cleanup.purge();
        assertThat(purged(runId)).isTrue();
        assertThat(gateway.callsTo("DELETE", forRun(GatewayApi.RUN_PATH, runId))).hasSize(2);
    }

    @Test
    void a_run_whose_numbers_the_reporting_service_still_holds_is_not_purged_yet() throws Exception {
        var runId = ended("RECONCILED", 25);
        ledger.status = 204;
        gateway.status = 204;
        reporting.status = 503;

        cleanup.purge();

        assertThat(purged(runId)).isFalse();
        assertThat(reporting.callsTo("DELETE", forRun(ReportingApi.RUN_PATH, runId))).hasSize(1);
    }
}
