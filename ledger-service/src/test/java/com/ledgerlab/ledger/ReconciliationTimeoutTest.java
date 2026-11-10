package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.ledger.api.RunController;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@AutoConfigureTestRestTemplate
@TestPropertySource(
    properties = {
        "ledger.reconciliation.statement-timeout=500ms",
        // One pooled connection: the reconciliation and the check after it share it.
        "spring.datasource.hikari.maximum-pool-size=1",
        // The outbox poll reads transfers: on the test's lock it would wait holding that one connection.
        "ledger.results.publish-interval=1h",
    })
class ReconciliationTimeoutTest extends IntegrationTest {

    private static final String SERVER_DEFAULT = "0";

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcConnectionDetails database;

    @Test
    void a_reconciliation_stuck_on_a_lock_answers_503_and_leaves_no_query_behind() throws SQLException {
        var runId = openRun();

        try (var lockHolder = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
            lockHolder.setAutoCommit(false);
            try (var statement = lockHolder.createStatement()) {
                // Ends the lock even if the reconciliation never times out, so a regression fails instead of hanging.
                statement.execute("SET idle_in_transaction_session_timeout = '10s'");
                statement.execute("LOCK TABLE ledger.transfers IN ACCESS EXCLUSIVE MODE");
            }
            try {
                var response = http.getForEntity("/internal/runs/" + runId + "/reconciliation", Map.class);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(response.getBody().get("error")).isEqualTo(RunController.STATEMENT_TIMEOUT);
                assertThat(activeReconciliationQueries(lockHolder)).isZero();
            } finally {
                lockHolder.rollback();
            }
        }
    }

    @Test
    @DisplayName("the statement timeout ends with the reconciliation's transaction")
    void the_statement_timeout_ends_with_the_reconciliations_transaction() {
        var runId = openRun();

        var response = http.getForEntity("/internal/runs/" + runId + "/reconciliation", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo(SERVER_DEFAULT);
    }

    private UUID openRun() {
        var runId = UUID.randomUUID();
        var body = Map.of("accountCount", 2, "openingBalanceCents", 100, "blockedAccounts", 0, "dailyLimitCents", 100);
        assertThat(http.postForEntity("/internal/runs/" + runId + "/accounts", body, List.class).getStatusCode())
            .isEqualTo(HttpStatus.CREATED);
        return runId;
    }

    private static int activeReconciliationQueries(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND query LIKE '%FROM transfers t%' AND pid <> pg_backend_pid()");
            var rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
