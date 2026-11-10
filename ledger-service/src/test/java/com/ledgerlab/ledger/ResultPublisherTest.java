package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.common.TransferResultEvent;
import com.ledgerlab.ledger.service.LedgerService;
import com.ledgerlab.ledger.service.RunService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.ObjectMapper;

class ResultPublisherTest extends IntegrationTest {

    @Autowired LedgerService ledger;
    @Autowired RunService runs;
    @Autowired JdbcClient jdbc;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired ObjectMapper objectMapper;
    @Value("${ledger.results.topic}") String resultsTopic;

    @Test
    void publishes_one_result_event_per_processed_transfer_and_marks_the_row_published() {
        var runId = UUID.randomUUID();
        runs.openAccounts(runId, setup(3, 1_000, 1));
        var posted = transfer(runId, 1, 2, 100);
        var rejected = transfer(runId, 1, 3, 100);

        var received = new ArrayList<TransferResultEvent>();
        var overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = consumerFactory.createConsumer("results-test-" + UUID.randomUUID(), null, null, overrides)) {
            consumer.subscribe(List.of(resultsTopic));

            applyOne(ledger, posted);
            applyOne(ledger, rejected);
            applyOne(ledger, posted); // duplicate delivery must not produce a second event

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record ->
                    received.add(objectMapper.readValue(record.value(), TransferResultEvent.class)));
                assertThat(received).filteredOn(event -> event.runId().equals(runId)).hasSize(2);
            });
        }

        var byTransfer = received.stream()
            .filter(event -> event.runId().equals(runId))
            .collect(Collectors.toMap(TransferResultEvent::transferId, Function.identity()));
        assertThat(byTransfer.get(posted.transferId()).outcome()).isEqualTo(TransferOutcome.POSTED);
        assertThat(byTransfer.get(rejected.transferId()).outcome()).isEqualTo(TransferOutcome.REJECTED);
        assertThat(byTransfer.get(rejected.transferId()).rejectReason()).isEqualTo(RejectReason.BLOCKED_ACCOUNT);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var unpublished = jdbc.sql("SELECT count(*) FROM transfers WHERE run_id = :r AND result_published_at IS NULL")
                .param("r", runId).query(Long.class).single();
            assertThat(unpublished).isZero();
        });
    }
}
