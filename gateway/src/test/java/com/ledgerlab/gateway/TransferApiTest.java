package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.gateway.domain.TransferStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;

class TransferApiTest extends IntegrationTest {

    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired JdbcTemplate jdbc;

    @Test
    void accepts_a_transfer_with_202_and_publishes_it_to_kafka_through_the_outbox() {
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (var consumer = consumerFactory.createConsumer("api-test-" + UUID.randomUUID(), null, null, overrides)) {
            consumer.subscribe(List.of(props.transfersTopic()));

            var response = submit(body(transferId, runId, 3, 7, 250));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().get("status")).isEqualTo(TransferStatus.ACCEPTED.name());

            var published = new ArrayList<TransferCommand>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> published.add(objectMapper.readValue(record.value(), TransferCommand.class)));
                assertThat(published).extracting(TransferCommand::transferId).contains(transferId);
            });
            var command = published.stream().filter(it -> it.transferId().equals(transferId)).findFirst().orElseThrow();
            assertThat(command).usingRecursiveComparison().ignoringFields("createdAt")
                .isEqualTo(new TransferCommand(runId, transferId, 3, 7, 250, command.createdAt()));
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(get("/v1/transfers/" + transferId).getBody().get("status")).isEqualTo(TransferStatus.PUBLISHED.name()));
    }

    @Test
    void a_repeated_submission_with_the_same_id_returns_the_existing_transfer() {
        var request = body();

        var first = submit(request);
        var second = submit(request);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isIn(HttpStatus.ACCEPTED, HttpStatus.OK);
        assertThat(second.getBody().get("transferId")).isEqualTo(first.getBody().get("transferId"));
        assertThat(second.getBody().get("acceptedAt")).isEqualTo(first.getBody().get("acceptedAt"));
    }

    @Test
    void submissions_of_one_id_racing_on_the_insert_all_get_the_one_transfer() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (var round = 0; round < 10; round++) {
                var request = body();
                var gate = new CountDownLatch(1);
                var responses = new ArrayList<Future<ResponseEntity<Map<String, Object>>>>();
                for (var i = 0; i < 8; i++) {
                    responses.add(pool.submit(() -> {
                        gate.await();
                        return submit(request);
                    }));
                }
                gate.countDown();
                var answers = new ArrayList<ResponseEntity<Map<String, Object>>>();
                for (var response : responses) {
                    answers.add(response.get());
                }

                assertThat(answers).allSatisfy(answer -> assertThat(answer.getStatusCode().is2xxSuccessful()).isTrue());
                assertThat(answers).extracting(answer -> answer.getBody().get("acceptedAt")).containsOnly(answers.getFirst().getBody().get("acceptedAt"));
                var rows = jdbc.queryForObject("SELECT count(*) FROM transfers WHERE transfer_id = ?", Integer.class, request.get("transferId"));
                assertThat(rows).isEqualTo(1);
            }
        }
    }

    @Test
    void the_same_id_with_a_different_payload_is_rejected_with_422() {
        var transferId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        submit(body(transferId, runId, 1, 2, 100));

        var conflict = submit(body(transferId, runId, 1, 2, 999));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void only_version_1_of_the_public_api_exists_and_internal_paths_are_unversioned() {
        var unknownVersion = http.postForEntity("/v2/transfers", body(), String.class);
        assertThat(unknownVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var unversioned = http.postForEntity("/transfers", body(), String.class);
        assertThat(unversioned.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var internal = get("/internal/runs/" + UUID.randomUUID() + "/summary");
        assertThat(internal.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void validates_the_request_and_answers_404_for_unknown_transfers() {
        var invalid = submit(bodyWithAmount(0));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var unknown = http.getForEntity("/v1/transfers/" + UUID.randomUUID(), String.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void a_submission_without_a_required_field_is_refused_as_a_bad_request() {
        var withoutTransferId = body();
        withoutTransferId.remove("transferId");
        var withoutAmount = body();
        withoutAmount.remove("amountCents");

        assertThat(submit(withoutTransferId).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(withoutAmount).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
