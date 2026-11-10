package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@AutoConfigureTestRestTemplate
class RunControllerTest extends IntegrationTest {

    @Autowired TestRestTemplate http;

    private static Map<String, Object> body(int accountCount, long openingBalanceCents, int blockedAccounts, long dailyLimitCents) {
        return Map.of(
            "accountCount", accountCount,
            "openingBalanceCents", openingBalanceCents,
            "blockedAccounts", blockedAccounts,
            "dailyLimitCents", dailyLimitCents);
    }

    @Test
    @DisplayName("opens accounts once, reconciles and deletes a run")
    void opens_accounts_once_reconciles_and_deletes_a_run() {
        var runId = UUID.randomUUID();
        var body = body(5, 1_000, 1, 500);

        var created = http.postForEntity("/internal/runs/" + runId + "/accounts", body, Object[].class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).hasSize(5);

        var again = http.postForEntity("/internal/runs/" + runId + "/accounts", body, Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var report = http.getForEntity("/internal/runs/" + runId + "/reconciliation", Map.class);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getBody().get("balanced")).isEqualTo(true);
        assertThat(report.getBody().get("accounts")).isEqualTo(5);

        http.delete("/internal/runs/" + runId);
        var afterDelete = http.getForEntity("/internal/runs/" + runId + "/reconciliation", Map.class);
        assertThat(afterDelete.getBody().get("accounts")).isEqualTo(0);
    }

    @Test
    void validates_the_account_setup() {
        var runId = UUID.randomUUID();
        var body = body(1, -5, 0, 0);

        var response = http.postForEntity("/internal/runs/" + runId + "/accounts", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejects_a_setup_that_would_block_every_account() {
        var runId = UUID.randomUUID();
        var body = body(5, 100, 5, 100);

        var response = http.postForEntity("/internal/runs/" + runId + "/accounts", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").toString()).contains("blockedAccounts");
    }

    @Test
    void concurrent_requests_to_open_the_same_run_yield_one_201_and_the_rest_409() throws Exception {
        var runId = UUID.randomUUID();
        var body = body(50, 100, 0, 100);
        Callable<HttpStatusCode> open = () -> http.postForEntity("/internal/runs/" + runId + "/accounts", body, String.class).getStatusCode();

        var pool = Executors.newFixedThreadPool(8);
        var statuses = new ArrayList<HttpStatusCode>();
        try {
            for (var future : pool.invokeAll(Collections.nCopies(8, open))) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).filteredOn(HttpStatus.CREATED::equals).hasSize(1);
        assertThat(statuses).filteredOn(HttpStatus.CONFLICT::equals).hasSize(7);
    }
}
