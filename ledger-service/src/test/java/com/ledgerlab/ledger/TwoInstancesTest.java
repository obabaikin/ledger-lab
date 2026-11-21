package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.kafka.TransferConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Two ledger instances on one database and one broker, as while a deploy replaces one pod: this test's context is the
 * first, a second application started beside it on the same containers is the other.
 */
class TwoInstancesTest extends IntegrationTest {

    private static final int PARTITIONS = 4;
    private static final int ACCOUNTS = 10;
    private static final long OPENING_BALANCE_CENTS = 100_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SHORT_POLL_INTERVAL = Duration.ofSeconds(3);

    @Autowired PostgreSQLContainer postgres;
    @Autowired KafkaContainer broker;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired KafkaProperties kafkaProperties;
    @Autowired ObjectMapper objectMapper;
    @Autowired MeterRegistry registry;
    @Value("${ledger.topic.name}") String topic;

    private final List<ConfigurableApplicationContext> others = new ArrayList<>();

    @AfterEach
    void stopTheOtherInstances() {
        others.forEach(ConfigurableApplicationContext::close);
        others.clear();
        await().atMost(TIMEOUT).until(() -> assigned(listeners).size() == PARTITIONS);
    }

    @Test
    @DisplayName("members join the group without a static id, under the cooperative assignor")
    void members_join_the_group_without_a_static_id_under_the_cooperative_assignor() throws Exception {
        start();

        try (var admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            var group = admin.describeConsumerGroups(List.of(groupId())).all().get().get(groupId());
            assertThat(group.partitionAssignor()).isEqualTo(CooperativeStickyAssignor.COOPERATIVE_STICKY_ASSIGNOR_NAME);
            assertThat(group.members()).hasSize(4).allSatisfy(member -> assertThat(member.groupInstanceId()).isEmpty());
        }
    }

    @Test
    @DisplayName("a started instance returns from its start only once it reads partitions, and its consumer waits for the warm-up")
    void a_started_instance_returns_from_its_start_only_once_it_reads_partitions() {
        var other = start();

        assertThat(assigned(other.getBean(KafkaListenerEndpointRegistry.class))).isNotEmpty();
        assertThat(container(listeners).isAutoStartup()).isFalse();
    }

    @Test
    @DisplayName("an instance that stops hands its partitions over well before the session timeout, its batches committed")
    void an_instance_that_stops_hands_its_partitions_over_well_before_the_session_timeout_its_batches_committed() throws Exception {
        openBank(ACCOUNTS, OPENING_BALANCE_CENTS);
        var other = start();
        await().atMost(TIMEOUT).until(() -> assigned(listeners).size() + assigned(other.getBean(KafkaListenerEndpointRegistry.class)).size() == PARTITIONS);
        var duplicatesBefore = duplicates(registry);
        var commands = IntStream.range(0, 400).mapToObj(i -> transfer(i % ACCOUNTS + 1, (i + 3) % ACCOUNTS + 1, 1)).toList();

        try (var sender = Executors.newSingleThreadExecutor()) {
            var sent = sender.submit(() -> {
                for (var command : commands) {
                    send(command, null);
                    Thread.sleep(2);
                }
                return null;
            });
            await().atMost(TIMEOUT).until(() -> reconciliation().postedTransfers() > 50);
            var stopping = System.nanoTime();
            other.close();
            await().atMost(TIMEOUT).until(() -> assigned(listeners).size() == PARTITIONS);
            var handover = Duration.ofNanos(System.nanoTime() - stopping);
            sent.get();

            assertThat(handover).isLessThan(sessionTimeout().dividedBy(2));
        }
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(reconciliation().postedTransfers()).isEqualTo(commands.size()));
        assertThat(duplicates(registry) - duplicatesBefore).isZero();
        await().atMost(TIMEOUT).until(() -> uncommitted() == 0);
        awaitCreditsApplied();
        assertTheBooksAgree();
        assertThat(balances().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(ACCOUNTS * OPENING_BALANCE_CENTS);
    }

    // Dropped from the group by its poll interval, as a GC pause past the session timeout would drop it: its partition
    // moves on while it still holds a batch.
    @Test
    @DisplayName("a batch an instance finishes after the group moved its partition on is posted once")
    void a_batch_an_instance_finishes_after_the_group_moved_its_partition_on_is_posted_once() throws Exception {
        openBank(ACCOUNTS, OPENING_BALANCE_CENTS);
        var other = start("--spring.kafka.consumer.properties.max.poll.interval.ms=" + SHORT_POLL_INTERVAL.toMillis());
        var otherListeners = other.getBean(KafkaListenerEndpointRegistry.class);
        await().atMost(TIMEOUT).until(() -> assigned(listeners).size() + assigned(otherListeners).size() == PARTITIONS);
        var partition = assigned(otherListeners).getFirst();
        var sender = 4;
        var commands = IntStream.range(0, 20).mapToObj(i -> transfer(sender, sender % ACCOUNTS + 1, 10)).toList();
        var duplicatesBefore = duplicates(registry) + duplicates(other.getBean(MeterRegistry.class));

        try (var lock = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT 1 FROM ledger.accounts WHERE account_no = ? FOR NO KEY UPDATE")) {
                statement.setInt(1, sender);
                statement.executeQuery().close();
            }
            for (var command : commands) {
                send(command, partition.partition());
            }
            await().atMost(TIMEOUT).until(() -> assigned(listeners).contains(partition));
            await().atMost(TIMEOUT).until(() -> batchesWaitingForTheLock() == 2);
            lock.rollback();
        }

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(reconciliation().postedTransfers()).isEqualTo(commands.size());
            assertThat(duplicates(registry) + duplicates(other.getBean(MeterRegistry.class)) - duplicatesBefore).isPositive();
        });
        awaitCreditsApplied();
        assertTheBooksAgree();
        assertThat(balances().get(sender)).isEqualTo(OPENING_BALANCE_CENTS - 10L * commands.size());
    }

    private ConfigurableApplicationContext start(String... arguments) {
        var all = new ArrayList<>(List.of(
            "--server.port=0",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.kafka.bootstrap-servers=" + broker.getBootstrapServers()));
        all.addAll(List.of(arguments));
        // Arguments, not default properties: those rank below application.yaml.
        var other = new SpringApplicationBuilder(LedgerApplication.class).profiles("test").run(all.toArray(String[]::new));
        others.add(other);
        return other;
    }

    private void send(TransferCommand command, Integer partition) throws Exception {
        kafka.send(topic, partition, command.partitionKey(), objectMapper.writeValueAsString(command)).get();
    }

    private long batchesWaitingForTheLock() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var result = connection.createStatement().executeQuery(
                 "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%FOR NO KEY UPDATE%'")) {
            result.next();
            return result.getLong(1);
        }
    }

    private long uncommitted() throws Exception {
        try (var admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            var committed = admin.listConsumerGroupOffsets(groupId()).partitionsToOffsetAndMetadata().get();
            var partitions = IntStream.range(0, PARTITIONS).mapToObj(p -> new TopicPartition(topic, p)).toList();
            var latest = admin.listOffsets(partitions.stream().collect(Collectors.toMap(p -> p, p -> OffsetSpec.latest()))).all().get();
            return partitions.stream()
                .mapToLong(p -> latest.get(p).offset() - (committed.containsKey(p) ? committed.get(p).offset() : 0))
                .sum();
        }
    }

    private String groupId() {
        return Objects.requireNonNull(kafkaProperties.getConsumer().getGroupId());
    }

    private Duration sessionTimeout() {
        return Duration.ofMillis(Long.parseLong(kafkaProperties.getConsumer().getProperties().get(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)));
    }

    private static double duplicates(MeterRegistry meters) {
        return meters.get(TransferConsumer.DUPLICATES_METRIC).counter().count();
    }

    private static MessageListenerContainer container(KafkaListenerEndpointRegistry registry) {
        return Objects.requireNonNull(registry.getListenerContainer(TransferConsumer.LISTENER_ID));
    }

    private static List<TopicPartition> assigned(KafkaListenerEndpointRegistry registry) {
        var partitions = container(registry).getAssignedPartitions();
        return partitions == null ? List.of() : List.copyOf(partitions);
    }
}
