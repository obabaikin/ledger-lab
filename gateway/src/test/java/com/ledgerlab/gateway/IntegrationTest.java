package com.ledgerlab.gateway;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.common.TransferResultEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.jspecify.annotations.Nullable;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, IntegrationTest.Topics.class})
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
abstract class IntegrationTest {

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

    protected static Map<String, Object> body() {
        return body(UUID.randomUUID(), UUID.randomUUID());
    }

    protected static Map<String, Object> bodyOfRun(UUID runId) {
        return body(UUID.randomUUID(), runId);
    }

    protected static Map<String, Object> bodyWithAmount(long amount) {
        return body(UUID.randomUUID(), UUID.randomUUID(), 1, 2, amount);
    }

    protected static Map<String, Object> body(UUID transferId, UUID runId) {
        return body(transferId, runId, 1, 2, 100);
    }

    protected static Map<String, Object> body(UUID transferId, UUID runId, int from, int to, long amount) {
        var body = new HashMap<String, Object>();
        body.put("transferId", transferId);
        body.put("runId", runId);
        body.put("fromAccount", from);
        body.put("toAccount", to);
        body.put("amountCents", amount);
        return body;
    }

    protected ResponseEntity<Map<String, Object>> submit(Map<String, Object> body) {
        return post("/v1/transfers", body);
    }

    protected ResponseEntity<Map<String, Object>> submit(Map<String, Object> body, String wait) {
        return post("/v1/transfers?wait=" + wait, body);
    }

    protected ResponseEntity<Map<String, Object>> post(String url, @Nullable Object body) {
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body), JSON_OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> get(String url) {
        return http.exchange(url, HttpMethod.GET, null, JSON_OBJECT);
    }

    protected void publishResult(UUID runId, UUID transferId, TransferOutcome outcome) throws Exception {
        publishResult(runId, transferId, outcome, null);
    }

    protected void publishResult(UUID runId, UUID transferId, TransferOutcome outcome, @Nullable RejectReason reason) throws Exception {
        var event = new TransferResultEvent(runId, transferId, outcome, reason, Instant.now());
        kafka.send(props.resultsTopic(), event.partitionKey(), objectMapper.writeValueAsString(event)).get();
    }

    /** A result event as a newer ledger might write it, with values this gateway may not know. */
    protected void publishRawResult(UUID runId, UUID transferId, String json) throws Exception {
        kafka.send(props.resultsTopic(), TransferResultEvent.partitionKey(runId, transferId), json).get();
    }
}
