package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.kafka.TransferConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
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
    @Autowired MeterRegistry registry;
    @Value("${ledger.topic.name}") String topic;

    @Test
    @DisplayName("consumes transfers from kafka, applies each exactly once and reconciles")
    void consumes_transfers_from_kafka_applies_each_exactly_once_and_reconciles() throws Exception {
        openBank(4, 1_000);
        var duplicatesBefore = duplicateCount();
        var commands = IntStream.rangeClosed(1, 40).mapToObj(i -> transfer(i % 4 + 1, (i + 1) % 4 + 1, 10)).toList();

        for (var command : commands) {
            send(command);
        }
        for (var command : commands.subList(0, 10)) {
            send(command);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(reconciliation().postedTransfers()).isEqualTo(40);
            assertThat(duplicateCount() - duplicatesBefore).isEqualTo(10.0);
        });
        awaitCreditsApplied();
        assertTheBooksAgree();
        assertThat(balances().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(4_000);
        // The names control queries in Prometheus (ADR 0015).
        assertThat(http.getForObject("/actuator/prometheus", String.class))
            .containsPattern("(?m)^ledger_duplicates_total [1-9]")
            .containsPattern("(?m)^ledger_partitions_ahead\\{table=\"transfers\"} [1-9]")
            .containsPattern("(?m)^ledger_kafka_assigned_partitions [1-9]");
    }

    private void send(TransferCommand command) throws Exception {
        kafka.send(topic, command.partitionKey(), objectMapper.writeValueAsString(command)).get();
    }

    private double duplicateCount() {
        return registry.get(TransferConsumer.DUPLICATES_METRIC).counter().count();
    }
}
