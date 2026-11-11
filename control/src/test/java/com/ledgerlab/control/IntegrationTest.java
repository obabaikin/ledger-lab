package com.ledgerlab.control;

import static com.ledgerlab.control.Fixtures.mapOf;

import com.ledgerlab.control.bank.BankLive;
import com.ledgerlab.control.periods.PeriodConductor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTest.Containers.class)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
abstract class IntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ControlProperties props;
    @Autowired BankLive bankLive;
    @Autowired PeriodConductor conductor;

    static final StubService ledger = new StubService();
    static final StubService gateway = new StubService();
    static final StubService generator = new StubService();
    static final StubService reporting = new StubService();
    static final StubService prometheus = new StubService();
    static final StubService turnstile = new StubService();
    static final StubService mailjet = new StubService();
    static final List<StubService> ledgerInstances = Stream.generate(StubService::new).limit(3).toList();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("control.ledger.base-url", () -> ledger.baseUrl);
        registry.add("control.gateway.base-url", () -> gateway.baseUrl);
        registry.add("control.generator.base-url", () -> generator.baseUrl);
        registry.add("control.reporting.base-url", () -> reporting.baseUrl);
        registry.add("stub.prometheus-url", () -> prometheus.baseUrl);
        registry.add("control.turnstile.base-url", () -> turnstile.baseUrl);
        registry.add("control.mail.base-url", () -> mailjet.baseUrl);
        for (var i = 0; i < ledgerInstances.size(); i++) {
            var index = i;
            var stub = ledgerInstances.get(i);
            registry.add("control.chaos.ledger-instances[" + index + "].name", () -> "ledger-" + index);
            registry.add("control.chaos.ledger-instances[" + index + "].url", () -> stub.baseUrl);
        }
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql(
            """
            TRUNCATE periods, availability_minutes, availability_hours, maintenance_windows, sessions, invite_visits, invite_mails,
                invite_requests, invite_emails, invite_tokens, chaos_events, load_pulses
            """).update();
        ledger.reset();
        gateway.reset();
        generator.reset();
        reporting.reset();
        prometheus.reset();
        turnstile.reset();
        mailjet.reset();
        ledgerInstances.forEach(StubService::reset);
        // The live numbers are kept between ticks; a test must not start with those an earlier test read.
        ReflectionTestUtils.setField(bankLive, "snapshot", ReflectionTestUtils.getField(BankLive.Snapshot.class, "EMPTY"));
        ReflectionTestUtils.setField(conductor, "settling", null);
    }

    protected String invite() {
        return invite(Duration.ofDays(1), true);
    }

    protected String invite(Duration expiresIn, boolean active) {
        var token = "invite-" + UUID.randomUUID();
        jdbc.sql(
            """
            INSERT INTO invite_tokens (token, expires_at, active)
            VALUES (:token, now() + CAST(:millis AS bigint) * interval '1 millisecond', :active)
            """)
            .param("token", token)
            .param("millis", expiresIn.toMillis())
            .param("active", active)
            .update();
        return token;
    }

    protected ResponseEntity<Map<String, Object>> post(String url, Object body) {
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body), JSON_OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> postEntity(String url, HttpEntity<?> entity) {
        return http.exchange(url, HttpMethod.POST, entity, JSON_OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> get(String url) {
        return http.exchange(url, HttpMethod.GET, null, JSON_OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> enter(String token) {
        return post("/api/sessions", mapOf("token", token));
    }

    protected UUID sessionOf(String token) {
        return UUID.fromString((String) enter(token).getBody().get("sessionId"));
    }

    protected ResponseEntity<String> requestLink(String email) {
        return requestLink(email, null);
    }

    protected ResponseEntity<String> requestLink(String email, String turnstileToken) {
        return http.postForEntity("/api/invite-requests", mapOf("email", email, "turnstileToken", turnstileToken), String.class);
    }

    protected static <T> T single(List<T> items) {
        if (items.size() != 1) {
            throw new IllegalArgumentException("expected one element, got " + items.size());
        }
        return items.getFirst();
    }
}
