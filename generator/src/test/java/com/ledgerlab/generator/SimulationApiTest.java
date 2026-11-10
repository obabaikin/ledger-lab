package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SimulationApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };
    private static final String SIMULATIONS = "/internal/simulations";

    private static final FakeGateway gateway = new FakeGateway();

    @DynamicPropertySource
    static void gatewayUrl(DynamicPropertyRegistry registry) {
        registry.add("generator.gateway-url", gateway::url);
    }

    @AfterAll
    static void shutdown() {
        gateway.close();
    }

    @Autowired TestRestTemplate http;

    private static Map<String, Object> request(UUID runId, int tps, int durationSeconds) {
        var request = new HashMap<String, Object>();
        request.put("runId", runId);
        request.put("tps", tps);
        request.put("durationSeconds", durationSeconds);
        request.put("accountCount", 10);
        return request;
    }

    private ResponseEntity<Map<String, Object>> post(String url, @Nullable Object body) {
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body), JSON_OBJECT);
    }

    private ResponseEntity<Map<String, Object>> get(String url) {
        return http.exchange(url, HttpMethod.GET, null, JSON_OBJECT);
    }

    private Map<String, Object> status(UUID runId) {
        return get(SIMULATIONS + "/" + runId).getBody();
    }

    @Test
    @DisplayName("starts, reports and finishes a simulation")
    void starts_reports_and_finishes_a_simulation() {
        var runId = UUID.randomUUID();
        var request = request(runId, 50, 1);
        request.put("blockedAccounts", 1);

        var started = post(SIMULATIONS, request);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(started.getBody().get("state")).isEqualTo("RUNNING");

        var again = post(SIMULATIONS, request);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var status = status(runId);
            assertThat(status.get("state")).isEqualTo("FINISHED");
            assertThat(status.get("acknowledged")).isEqualTo(status.get("requested"));
            assertThat(((Number) status.get("requested")).intValue()).isBetween(40, 60);
        });
    }

    @Test
    void a_stop_is_accepted_at_once_and_completes_in_the_background() {
        var runId = UUID.randomUUID();
        var held = new CountDownLatch(1);
        gateway.answersHeld = held;
        ResponseEntity<Map<String, Object>> stopping;
        try {
            var sentBefore = gateway.requests.get();
            post(SIMULATIONS, request(runId, 50, 30));
            await().atMost(Duration.ofSeconds(5)).until(() -> gateway.requests.get() > sentBefore);
            stopping = post(SIMULATIONS + "/" + runId + "/stop", null);
        } finally {
            gateway.answersHeld = null;
            held.countDown();
        }

        assertThat(stopping.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var status = status(runId);
            assertThat(status.get("state")).isEqualTo("STOPPED");
            assertThat(status.get("inFlight")).isEqualTo(0);
        });
    }

    @Test
    void a_stop_after_the_run_has_ended_answers_200_with_the_final_state() {
        var runId = UUID.randomUUID();
        post(SIMULATIONS, request(runId, 20, 1));
        await().atMost(Duration.ofSeconds(15)).until(() -> "FINISHED".equals(status(runId).get("state")));

        var stop = post(SIMULATIONS + "/" + runId + "/stop", null);

        assertThat(stop.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stop.getBody().get("state")).isEqualTo("FINISHED");
    }

    @Test
    void rejects_an_invalid_request() {
        var response = http.postForEntity(SIMULATIONS, request(UUID.randomUUID(), 0, 1), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void a_request_without_a_required_field_is_refused_as_a_bad_request() {
        var withoutTps = request(UUID.randomUUID(), 10, 1);
        withoutTps.remove("tps");
        var withoutRunId = request(UUID.randomUUID(), 10, 1);
        withoutRunId.remove("runId");

        for (var request : List.of(withoutTps, withoutRunId)) {
            assertThat(http.postForEntity(SIMULATIONS, request, String.class).getStatusCode()).as(request.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void an_explicit_null_in_a_field_with_a_default_is_refused_as_a_bad_request() {
        for (var field : List.of("blockedAccounts", "hotAccounts", "waitShare", "waitTimeoutSeconds")) {
            var request = request(UUID.randomUUID(), 10, 1);
            request.put(field, null);

            assertThat(http.postForEntity(SIMULATIONS, request, String.class).getStatusCode()).as(field).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void serves_the_journal_of_a_run_by_page_and_by_transfer() {
        var runId = UUID.randomUUID();
        post(SIMULATIONS, request(runId, 50, 1));
        await().atMost(Duration.ofSeconds(15)).until(() -> "FINISHED".equals(status(runId).get("state")));
        var requested = ((Number) status(runId).get("requested")).intValue();

        var first = get(SIMULATIONS + "/" + runId + "/transfers?limit=10").getBody();
        var items = (List<?>) first.get("items");
        assertThat(items).hasSize(10);
        assertThat(first.get("next")).isEqualTo(10);

        var all = get(SIMULATIONS + "/" + runId + "/transfers?outcome=ACKNOWLEDGED&limit=1000").getBody();
        assertThat((List<?>) all.get("items")).hasSize(requested);
        assertThat(all.get("next")).isNull();

        var entry = (Map<?, ?>) items.getFirst();
        assertThat(entry.get("seq")).isEqualTo(1);
        assertThat(((Map<?, ?>) entry.get("first")).get("result")).isEqualTo("RESPONSE");
        var one = get(SIMULATIONS + "/" + runId + "/transfers/" + entry.get("transferId"));
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody()).isEqualTo(entry);
    }

    @Test
    void answers_404_for_an_unknown_run_or_transfer_and_400_for_a_bad_page() {
        var runId = UUID.randomUUID();
        post(SIMULATIONS, request(runId, 10, 1));

        var noRun = get(SIMULATIONS + "/" + UUID.randomUUID() + "/transfers");
        var noTransfer = get(SIMULATIONS + "/" + runId + "/transfers/" + UUID.randomUUID());
        assertThat(noRun.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((String) noRun.getBody().get("error")).contains("No simulation");
        assertThat(noTransfer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((String) noTransfer.getBody().get("error")).contains("No transfer");
        for (var path : List.of("", "/reconciliation")) {
            var unknown = get(SIMULATIONS + "/" + UUID.randomUUID() + path);
            assertThat(unknown.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat((String) unknown.getBody().get("error")).as(path).contains("No simulation");
        }
        var unknownStop = post(SIMULATIONS + "/" + UUID.randomUUID() + "/stop", null);
        assertThat(unknownStop.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((String) unknownStop.getBody().get("error")).contains("No simulation");
        for (var query : List.of("limit=0", "limit=1001", "after=-1", "outcome=NOPE")) {
            assertThat(http.getForEntity(SIMULATIONS + "/" + runId + "/transfers?" + query, String.class).getStatusCode())
                .as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void rejects_a_run_beyond_the_generator_limits() {
        for (var request : List.of(request(UUID.randomUUID(), 2_001, 1), request(UUID.randomUUID(), 10, 31))) {
            assertThat(http.postForEntity(SIMULATIONS, request, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void reconciling_a_run_that_is_still_sending_answers_409_run_still_active() {
        var runId = UUID.randomUUID();
        post(SIMULATIONS, request(runId, 10, 10));

        var early = get(SIMULATIONS + "/" + runId + "/reconciliation");

        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody().get("error")).isEqualTo(GeneratorErrors.RUN_STILL_ACTIVE);
        post(SIMULATIONS + "/" + runId + "/stop", null);
    }
}
