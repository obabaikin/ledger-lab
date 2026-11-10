package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.gateway.domain.TransferStatus;
import com.ledgerlab.gateway.service.ResultWaiters;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two gateway instances on one database and one broker, as during a deploy, when the new pod starts before the old
 * one stops: this test's context is the first, a second application started beside it on the same containers is the other.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// One recorder per instance on the two result partitions, so each instance records one of them.
@TestPropertySource(properties = "spring.kafka.listener.concurrency=" + TwoInstancesTest.RECORDERS_PER_INSTANCE)
class TwoInstancesTest extends IntegrationTest {

    static final int RECORDERS_PER_INSTANCE = 1;
    private static final String RECORDER_GROUP = "gateway";
    private static final int RESULT_PARTITIONS = 2;
    private static final int TRANSFERS = 20;
    private static final int PUBLISHED = 400;
    private static final Duration SHUTDOWN_UNDER_WAY = Duration.ofSeconds(1);

    @Autowired PostgreSQLContainer postgres;
    @Autowired KafkaContainer broker;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired ResultWaiters waiters;

    private ConfigurableApplicationContext other;

    private record Answered(ResponseEntity<Map<String, Object>> answer, long atNanos) {
    }

    private static String urlOf(ConfigurableApplicationContext instance) {
        return "http://localhost:" + instance.getEnvironment().getProperty("local.server.port");
    }

    private ConfigurableApplicationContext startInstance(ApplicationListener<?>... listeners) {
        return new SpringApplicationBuilder(GatewayApplication.class)
            .profiles("test")
            .listeners(listeners)
            // Arguments, not default properties: those rank below application.yaml.
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.kafka.bootstrap-servers=" + broker.getBootstrapServers(),
                "--spring.kafka.listener.concurrency=" + RECORDERS_PER_INSTANCE);
    }

    private void awaitEachOwnsItsShare() {
        await().atMost(Duration.ofSeconds(60)).until(() ->
            ownsItsShare(registry) && ownsItsShare(other.getBean(KafkaListenerEndpointRegistry.class)));
    }

    @BeforeAll
    void startTheOtherInstance() {
        other = startInstance();
        awaitEachOwnsItsShare();
    }

    @AfterAll
    void stopTheOtherInstance() {
        other.close();
    }

    @Test
    void the_same_transfer_sent_to_two_gateway_instances_is_accepted_once_and_both_answer_it_the_same() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (var round = 0; round < 10; round++) {
                var request = body();
                var gate = new CountDownLatch(1);
                var responses = new ArrayList<Future<ResponseEntity<Map<String, Object>>>>();
                for (var i = 0; i < 8; i++) {
                    var base = i % 2 == 0 ? "" : urlOf(other);
                    responses.add(pool.submit(() -> {
                        gate.await();
                        return post(base + "/v1/transfers", request);
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
    void a_waiting_request_on_one_instance_gets_the_result_the_other_instance_recorded() throws Exception {
        var runId = UUID.randomUUID();
        var transferIds = Stream.generate(UUID::randomUUID).limit(TRANSFERS).toList();

        try (var pool = Executors.newFixedThreadPool(TRANSFERS)) {
            var pending = new ArrayList<Future<Answered>>();
            for (var id : transferIds) {
                pending.add(pool.submit(() -> new Answered(submit(body(id, runId), "PT5S"), System.nanoTime())));
            }
            await().atMost(Duration.ofSeconds(10)).until(() -> waiters.size() == TRANSFERS);

            for (var id : transferIds) {
                publishResult(runId, id, TransferOutcome.POSTED);
            }
            var published = System.nanoTime();

            var answers = new ArrayList<Answered>();
            for (var future : pending) {
                answers.add(future.get());
            }
            assertThat(answers).allSatisfy(answered -> {
                assertThat(answered.answer().getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(answered.answer().getBody().get("status")).isEqualTo(TransferStatus.POSTED.name());
            });
            // A request nobody wakes still reads the row when its wait ends; only the time tells the two apart.
            var slowest = Duration.ofNanos(answers.stream().mapToLong(Answered::atNanos).max().orElseThrow() - published);
            assertThat(slowest).isLessThan(props.maxWait().dividedBy(2));
        }
    }

    @Test
    void with_two_publishers_every_transfer_reaches_Kafka_once() throws Exception {
        var runId = UUID.randomUUID();
        var transferIds = Stream.generate(UUID::randomUUID).limit(PUBLISHED).toList();
        var overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (var consumer = consumerFactory.createConsumer("two-instances-" + UUID.randomUUID(), null, null, overrides)) {
            consumer.subscribe(List.of(props.transfersTopic()));

            try (var pool = Executors.newFixedThreadPool(8)) {
                var submitting = new ArrayList<Future<?>>();
                for (var i = 0; i < transferIds.size(); i++) {
                    var base = i % 2 == 0 ? "" : urlOf(other);
                    var id = transferIds.get(i);
                    submitting.add(pool.submit(() -> post(base + "/v1/transfers", body(id, runId))));
                }
                for (var future : submitting) {
                    future.get();
                }
            }

            var published = new ArrayList<UUID>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> published.add(objectMapper.readValue(record.value(), TransferCommand.class).transferId()));
                assertThat(published).filteredOn(transferIds::contains).containsAll(transferIds);
            });
            consumer.poll(Duration.ofSeconds(1)).forEach(record -> published.add(objectMapper.readValue(record.value(), TransferCommand.class).transferId()));
            assertThat(published).filteredOn(transferIds::contains).doesNotHaveDuplicates();
        }
    }

    @Test
    void a_request_waiting_on_an_instance_that_is_stopping_is_still_woken_by_its_result() throws Exception {
        var closing = new CountDownLatch(1);
        var stopping = startInstance((ApplicationListener<ContextClosedEvent>) event -> closing.countDown());
        var stoppingWaiters = stopping.getBean(ResultWaiters.class);
        await().atMost(Duration.ofSeconds(60)).until(() -> hasPartitions(notifierOf(stopping.getBean(KafkaListenerEndpointRegistry.class))));
        var runId = UUID.randomUUID();
        var transferId = UUID.randomUUID();

        try (var pool = Executors.newFixedThreadPool(2)) {
            var pending = pool.submit(() ->
                new Answered(post(urlOf(stopping) + "/v1/transfers?wait=PT5S", body(transferId, runId)), System.nanoTime()));
            await().atMost(Duration.ofSeconds(10)).until(() -> stoppingWaiters.size() == 1);

            var closed = pool.submit(stopping::close);
            closing.await();
            // Long enough for listeners that stop first to be gone, short of the drain it is waiting in.
            Thread.sleep(SHUTDOWN_UNDER_WAY.toMillis());
            publishResult(runId, transferId, TransferOutcome.POSTED);
            var published = System.nanoTime();

            var answered = pending.get();
            assertThat(answered.answer().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answered.answer().getBody().get("status")).isEqualTo(TransferStatus.POSTED.name());
            assertThat(Duration.ofNanos(answered.atNanos() - published)).isLessThan(props.maxWait().dividedBy(2));
            closed.get();
        }
        awaitEachOwnsItsShare();
    }

    private static boolean ownsItsShare(KafkaListenerEndpointRegistry listeners) {
        var recorder = listeners.getAllListenerContainers().stream().filter(it -> RECORDER_GROUP.equals(it.getGroupId())).toList();
        assertThat(recorder).hasSize(1);
        var assigned = recorder.getFirst().getAssignedPartitions();
        return assigned != null && assigned.size() == RESULT_PARTITIONS / 2 && hasPartitions(notifierOf(listeners));
    }

    private static MessageListenerContainer notifierOf(KafkaListenerEndpointRegistry listeners) {
        var notifiers = listeners.getAllListenerContainers().stream().filter(it -> !RECORDER_GROUP.equals(it.getGroupId())).toList();
        assertThat(notifiers).hasSize(1);
        return notifiers.getFirst();
    }

    private static boolean hasPartitions(MessageListenerContainer container) {
        var assigned = container.getAssignedPartitions();
        return assigned != null && !assigned.isEmpty();
    }
}
