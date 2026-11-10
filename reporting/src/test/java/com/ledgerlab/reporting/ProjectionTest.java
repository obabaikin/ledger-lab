package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.common.TransferResultEvent;
import com.ledgerlab.reporting.persistence.RunStatsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ProjectionTest extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String UNKNOWN_REASON = "ACCOUNT_FROZEN";
    private static final String REJECT_REASON_FIELD = "rejectReason";

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper objectMapper;
    @Autowired RunStatsRepository stats;
    @Autowired ReportingProperties props;

    @Test
    @DisplayName("commands and results from both topics, some delivered twice and results first, add up once")
    void commands_and_results_from_both_topics_some_delivered_twice_and_results_first_add_up_once() throws Exception {
        var runId = UUID.randomUUID();
        var now = Instant.now();
        var commands = Stream.generate(() -> command(runId, 10, now)).limit(30).toList();
        var results = new ArrayList<TransferResultEvent>();
        for (var i = 0; i < commands.size(); i++) {
            var c = commands.get(i);
            results.add(i % 3 == 0 ? rejected(c, RejectReason.DAILY_LIMIT_EXCEEDED, now.plusMillis(50)) : posted(c, now.plusMillis(50)));
        }

        for (var result : results.subList(0, 10)) {
            send(result);
        }
        for (var command : commands) {
            send(command);
        }
        for (var command : commands.subList(0, 5)) {
            send(command);
        }
        for (var result : results.subList(10, results.size())) {
            send(result);
        }
        for (var result : results.subList(0, 5)) {
            send(result);
        }

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var totals = stats.snapshot(runId).totals();
            assertThat(totals.accepted()).isEqualTo(30);
            assertThat(totals.posted()).isEqualTo(20);
            assertThat(totals.rejected()).isEqualTo(10);
            assertThat(totals.inFlight()).isZero();
            assertThat(totals.postedAmountCents()).isEqualTo(200);
            assertThat(totals.rejectedByReason()).containsOnlyKeys(RejectReason.DAILY_LIMIT_EXCEEDED.name());
        });
    }

    @Test
    void a_reject_reason_this_service_does_not_know_is_kept_as_sent() throws Exception {
        var runId = UUID.randomUUID();
        var command = command(runId, 10, Instant.now());
        var event = objectMapper.<ObjectNode>valueToTree(rejected(command, RejectReason.BLOCKED_ACCOUNT, Instant.now()))
            .put(REJECT_REASON_FIELD, UNKNOWN_REASON);

        send(command);
        kafka.send(props.resultsTopic(), command.transferId().toString(), objectMapper.writeValueAsString(event)).get();

        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(stats.snapshot(runId).totals().rejectedByReason()).containsEntry(UNKNOWN_REASON, 1L));
    }

    @Test
    void an_unreadable_event_is_skipped_and_the_ones_after_it_are_stored() throws Exception {
        var runId = UUID.randomUUID();
        var command = command(runId, 10, Instant.now());

        kafka.send(props.transfersTopic(), command.partitionKey(), "not json").get();
        send(command);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(stats.snapshot(runId).totals().accepted()).isEqualTo(1));
    }

    @Test
    void an_event_without_a_required_field_is_skipped_and_the_ones_after_it_are_stored() throws Exception {
        var runId = UUID.randomUUID();
        var command = command(runId, 10, Instant.now());
        var withoutTransferId = objectMapper.<ObjectNode>valueToTree(command(runId, 10, Instant.now()));
        withoutTransferId.remove("transferId");

        kafka.send(props.transfersTopic(), command.partitionKey(), objectMapper.writeValueAsString(withoutTransferId)).get();
        send(command);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(stats.snapshot(runId).totals().accepted()).isEqualTo(1));
    }

    private void send(TransferCommand command) throws Exception {
        kafka.send(props.transfersTopic(), command.partitionKey(), objectMapper.writeValueAsString(command)).get();
    }

    private void send(TransferResultEvent result) throws Exception {
        kafka.send(props.resultsTopic(), result.partitionKey(), objectMapper.writeValueAsString(result)).get();
    }
}
