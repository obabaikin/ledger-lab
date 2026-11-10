package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.books;
import static com.ledgerlab.control.RunFixtures.stats;
import static com.ledgerlab.control.RunFixtures.summary;
import static com.ledgerlab.control.http.ServiceHttp.forRun;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.gateway.GatewayClient;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.ledger.LedgerClient;
import com.ledgerlab.control.reporting.ReportingApi;
import com.ledgerlab.control.reporting.ReportingClient;
import com.ledgerlab.control.reporting.RunStats;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
        "control.services.request-timeout=300ms",
        "control.ledger.reconciliation-timeout=3s",
        "control.gateway.summary-timeout=3s",
        "control.reporting.final-stats-timeout=3s",
    })
class WholeRunReadTimeoutTest extends IntegrationTest {

    @Autowired LedgerClient ledgerClient;
    @Autowired GatewayClient gatewayClient;
    @Autowired ReportingClient reportingClient;

    private final Duration slower = Duration.ofMillis(900);

    @Test
    @DisplayName("the reads of a whole run wait their own timeout, longer than a plain request's")
    void the_reads_of_a_whole_run_wait_their_own_timeout_longer_than_a_plain_requests() throws Exception {
        var runId = UUID.randomUUID();
        ledger.answer("GET", forRun(LedgerApi.RECONCILIATION_PATH, runId), 200, books(runId), slower);
        gateway.answer("GET", forRun(GatewayApi.SUMMARY_PATH, runId), 200, summary(runId), slower);
        reporting.answer("GET", forRun(ReportingApi.STATS_PATH, runId), 200, stats(runId), slower);

        assertThat(ledgerClient.reconciliation(runId)).isInstanceOf(Reply.Answered.class);
        assertThat(gatewayClient.summary(runId)).isInstanceOf(Reply.Answered.class);
        assertThat(reportingClient.finalStats(runId)).isInstanceOf(Reply.Answered.class);
        assertThat(reportingClient.stats(runId)).isEqualTo(new Reply.NoAnswer<RunStats>());
    }
}
