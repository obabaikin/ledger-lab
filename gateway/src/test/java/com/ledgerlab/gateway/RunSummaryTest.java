package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RunSummaryTest extends IntegrationTest {

    @Test
    void summarizes_a_run_and_deletes_it() throws Exception {
        var runId = UUID.randomUUID();
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ids.forEach(id -> submit(body(id, runId)));
        publishResult(runId, ids.get(0), TransferOutcome.POSTED);
        publishResult(runId, ids.get(1), TransferOutcome.REJECTED, RejectReason.BLOCKED_ACCOUNT);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var summary = get("/internal/runs/" + runId + "/summary").getBody();
            assertThat(summary.get("accepted")).isEqualTo(3);
            assertThat(summary.get("published")).isEqualTo(3);
            assertThat(summary.get("posted")).isEqualTo(1);
            assertThat(summary.get("rejected")).isEqualTo(1);
            assertThat(summary.get("rejectionsByReason")).isEqualTo(Map.of(RejectReason.BLOCKED_ACCOUNT.name(), 1));
            assertThat(summary.get("latencyP50Ms")).isNotNull();
        });

        http.delete("/internal/runs/" + runId);
        var after = get("/internal/runs/" + runId + "/summary").getBody();
        assertThat(after.get("accepted")).isEqualTo(0);
        assertThat(http.getForEntity("/v1/transfers/" + ids.get(0), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
