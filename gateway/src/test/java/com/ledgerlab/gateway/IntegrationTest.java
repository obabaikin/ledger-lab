package com.ledgerlab.gateway;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.common.TransferResultEvent;
import com.ledgerlab.gateway.domain.Period;
import com.ledgerlab.gateway.domain.TransferStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * One bank for all tests of a context: each test starts with no transfers, keys or closed periods, and puts the
 * transfers of a period it closes into the last ended one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, IntegrationTest.Topics.class})
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
abstract class IntegrationTest {

    protected static final String TRANSFERS_PATH = "/v2/transfers";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    /** In production topics are created by infrastructure (infra/kafka), not by the services. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic transfersTopic(GatewayProperties props) {
            return new NewTopic(props.transfersTopic(), 2, (short) 1);
        }

        @Bean
        NewTopic resultsTopic(GatewayProperties props) {
            return new NewTopic(props.resultsTopic(), 2, (short) 1);
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper objectMapper;
    @Autowired GatewayProperties props;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyBank() {
        jdbcTemplate.update("DELETE FROM transfers");
        jdbcTemplate.update("DELETE FROM transfer_keys");
        jdbcTemplate.update("DELETE FROM periods");
    }

    protected static Map<String, Object> body() {
        return body(UUID.randomUUID());
    }

    protected static Map<String, Object> bodyWithAmount(long amount) {
        return body(UUID.randomUUID(), 1, 2, amount);
    }

    protected static Map<String, Object> body(UUID transferId) {
        return body(transferId, 1, 2, 100);
    }

    protected static Map<String, Object> body(UUID transferId, int from, int to, long amount) {
        var body = new HashMap<String, Object>();
        body.put("transferId", transferId);
        body.put("fromAccount", from);
        body.put("toAccount", to);
        body.put("amountCents", amount);
        return body;
    }

    protected ResponseEntity<Map<String, Object>> submit(Map<String, Object> body) {
        return post(TRANSFERS_PATH, body);
    }

    protected ResponseEntity<Map<String, Object>> submit(Map<String, Object> body, String wait) {
        return post(TRANSFERS_PATH + "?wait=" + wait, body);
    }

    protected ResponseEntity<Map<String, Object>> status(UUID transferId) {
        return get(TRANSFERS_PATH + "/" + transferId);
    }

    protected ResponseEntity<Map<String, Object>> post(String url, @Nullable Object body) {
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body), JSON_OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> get(String url) {
        return http.exchange(url, HttpMethod.GET, null, JSON_OBJECT);
    }

    protected Period lastEndedPeriod() {
        return Period.containing(Instant.now(), props.periods().length()).previous();
    }

    protected ResponseEntity<Map<String, Object>> close(Period period) {
        return post("/internal/periods/" + period.start() + "/close", null);
    }

    protected ResponseEntity<Map<String, Object>> summary(Period period) {
        return get("/internal/periods/" + period.start() + "/summary");
    }

    /** A transfer as the gateway stored it at that time, its command already sent; the database's clock cannot be set. */
    protected UUID acceptedAt(Instant acceptedAt) {
        return stored(acceptedAt, TransferStatus.PUBLISHED, null, null);
    }

    protected UUID stored(Instant acceptedAt, TransferStatus status, @Nullable RejectReason reason, @Nullable Instant resultedAt) {
        var transferId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO transfer_keys (transfer_id, accepted_at) VALUES (?, ?)", transferId, Timestamp.from(acceptedAt));
        jdbcTemplate.update(
            """
            INSERT INTO transfers (accepted_at, transfer_id, from_account, to_account, amount_cents, status, reject_reason, published_at, resulted_at)
            VALUES (?, ?, 1, 2, 100, ?, ?, ?, ?)
            """,
            Timestamp.from(acceptedAt),
            transferId,
            status.name(),
            reason == null ? null : reason.name(),
            Timestamp.from(acceptedAt),
            resultedAt == null ? null : Timestamp.from(resultedAt));
        return transferId;
    }

    protected long transferRows(Object transferId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE transfer_id = CAST(? AS uuid)", Long.class, transferId.toString());
    }

    protected Instant storedAcceptedAt(UUID transferId) {
        return jdbcTemplate.queryForObject("SELECT accepted_at FROM transfer_keys WHERE transfer_id = ?", Timestamp.class, transferId).toInstant();
    }

    protected void publishResult(UUID transferId, TransferOutcome outcome) throws Exception {
        publishResult(transferId, outcome, null);
    }

    /** As the ledger answers: the event repeats the time the gateway accepted the transfer. */
    protected void publishResult(UUID transferId, TransferOutcome outcome, @Nullable RejectReason reason) throws Exception {
        var event = new TransferResultEvent(transferId, outcome, reason, Instant.now(), storedAcceptedAt(transferId));
        kafka.send(props.resultsTopic(), event.partitionKey(), objectMapper.writeValueAsString(event)).get();
    }

    /** A result event as a newer ledger might write it, with values this gateway may not know. */
    protected void publishRawResult(UUID transferId, String json) throws Exception {
        kafka.send(props.resultsTopic(), transferId.toString(), json).get();
    }
}
