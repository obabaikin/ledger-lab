package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.metrics.RunDuplicates;
import com.ledgerlab.ledger.service.RunService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureTestRestTemplate
class TransferConsumerTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper objectMapper;
    @Autowired RunService runs;
    @Autowired MeterRegistry registry;
    @Value("${ledger.topic.name}") String topic;

    @Test
    @DisplayName("consumes transfers from kafka, applies each exactly once and reconciles")
    void consumes_transfers_from_kafka_applies_each_exactly_once_and_reconciles() throws Exception {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(4, 1_000));
        var commands = IntStream.rangeClosed(1, 40).mapToObj(i -> transfer(runId, i % 4 + 1, (i + 1) % 4 + 1, 10)).toList();

        for (var command : commands) {
            send(command);
        }
        for (var command : commands.subList(0, 10)) {
            send(command);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(runs.reconcile(runId).postedTransfers()).isEqualTo(40);
            assertThat(duplicateCount(runId)).isEqualTo(10.0);
        });
        var report = runs.reconcile(runId);
        assertThat(report.balanced()).isTrue();
        assertThat(report.balanceTotalCents()).isEqualTo(4_000);
        // The names control queries in Prometheus (ADR 0015).
        assertThat(http.getForObject("/actuator/prometheus", String.class))
            .contains("ledger_duplicates_total{run=\"" + runId + "\"} 10.0")
            .containsPattern("(?m)^ledger_kafka_assigned_partitions [1-9]");
    }

    private void send(TransferCommand command) throws Exception {
        kafka.send(topic, command.partitionKey(), objectMapper.writeValueAsString(command)).get();
    }

    private double duplicateCount(UUID runId) {
        var counter = registry.find(RunDuplicates.METRIC).tag(RunDuplicates.RUN_TAG, runId.toString()).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
