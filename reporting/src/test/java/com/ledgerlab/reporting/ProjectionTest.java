package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.common.TransferResultEvent;
import com.ledgerlab.reporting.domain.Period;
import com.ledgerlab.reporting.domain.PeriodStats;
import com.ledgerlab.reporting.kafka.BankLatency;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    @Autowired MeterRegistry registry;

    @Test
    @DisplayName("commands and results from both topics, some delivered twice and results first, add up once")
    void commands_and_results_from_both_topics_some_delivered_twice_and_results_first_add_up_once() throws Exception {
        var now = Instant.now();
        var latencyCountBefore = registry.get(BankLatency.METRIC).timer().count();
        var commands = Stream.generate(() -> command(10, now)).limit(30).toList();
        var results = new ArrayList<TransferResultEvent>();
        for (var i = 0; i < commands.size(); i++) {
            var c = commands.get(i);
            results.add(i % 3 == 0 ? rejected(c, RejectReason.INSUFFICIENT_FUNDS, now.plusMillis(50)) : posted(c, now.plusMillis(50)));
        }

        // The repeats go first: the last event of every partition is one the numbers below wait for, so nothing of
        // this test is still on its way when the next one empties the bank.
        for (var command : commands.subList(0, 5)) {
            send(command);
        }
        for (var result : results.subList(0, 10)) {
            send(result);
        }
        for (var result : results.subList(0, 5)) {
            send(result);
        }
        for (var command : commands) {
            send(command);
        }
        for (var result : results.subList(10, results.size())) {
            send(result);
        }

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var stats = statsAt(now);
            assertThat(stats.accepted()).isEqualTo(30);
            assertThat(stats.commandsSeen()).isEqualTo(30);
            assertThat(stats.posted()).isEqualTo(20);
            assertThat(stats.rejected()).isEqualTo(10);
            assertThat(stats.postedAmountCents()).isEqualTo(200);
            assertThat(stats.rejectionsByReason()).containsExactly(entry(RejectReason.INSUFFICIENT_FUNDS.name(), 10L));
        });
        assertThat(registry.get(BankLatency.METRIC).timer().count() - latencyCountBefore).isGreaterThanOrEqualTo(30);
    }

    @Test
    void a_reject_reason_this_service_does_not_know_is_kept_as_sent() throws Exception {
        var now = Instant.now();
        var command = command(10, now);
        var event = objectMapper.<ObjectNode>valueToTree(rejected(command, RejectReason.BLOCKED_ACCOUNT, Instant.now()))
            .put(REJECT_REASON_FIELD, UNKNOWN_REASON);

        send(command);
        kafka.send(props.resultsTopic(), command.transferId().toString(), objectMapper.writeValueAsString(event)).get();

        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(statsAt(now).rejectionsByReason()).containsExactly(entry(UNKNOWN_REASON, 1L)));
    }

    @Test
    void an_unreadable_event_is_skipped_and_the_ones_after_it_are_stored() throws Exception {
        var now = Instant.now();
        var command = command(10, now);

        kafka.send(props.transfersTopic(), command.partitionKey(), "not json").get();
        send(command);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statsAt(now).accepted()).isEqualTo(1));
    }

    @Test
    void an_event_without_a_required_field_is_skipped_and_the_ones_after_it_are_stored() throws Exception {
        var now = Instant.now();
        var command = command(10, now);
        var withoutTransferId = objectMapper.<ObjectNode>valueToTree(command(10, now));
        withoutTransferId.remove("transferId");

        kafka.send(props.transfersTopic(), command.partitionKey(), objectMapper.writeValueAsString(withoutTransferId)).get();
        send(command);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statsAt(now).accepted()).isEqualTo(1));
    }

    private PeriodStats statsAt(Instant acceptedAt) {
        return seconds.sum(Period.containing(acceptedAt, props.periods().length()));
    }

    private void send(TransferCommand command) throws Exception {
        kafka.send(props.transfersTopic(), command.partitionKey(), objectMapper.writeValueAsString(command)).get();
    }

    private void send(TransferResultEvent result) throws Exception {
        kafka.send(props.resultsTopic(), result.partitionKey(), objectMapper.writeValueAsString(result)).get();
    }
}
