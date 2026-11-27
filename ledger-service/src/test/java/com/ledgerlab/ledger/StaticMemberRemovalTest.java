package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.ledger.kafka.StaticMemberRemoval;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.testcontainers.kafka.KafkaContainer;

class StaticMemberRemovalTest extends IntegrationTest {

    private static final String GROUP = "ledger-static-test";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(100);
    // Long enough that the broker does not drop a closed static member on its own while the test runs.
    private static final int SESSION_TIMEOUT_MS = 120_000;

    @Autowired KafkaContainer broker;
    @Value("${ledger.topic.name}") String topic;

    @Test
    void the_static_members_of_a_stopped_pod_are_removed_and_the_other_pods_are_kept() throws Exception {
        joinAndStop("ledger-1-1", "ledger-1-2", "ledger-0-1");

        try (var admin = admin()) {
            var removal = new StaticMemberRemoval(admin, GROUP);
            assertThat(removal.staticMembers("")).containsExactly("ledger-0-1", "ledger-1-1", "ledger-1-2");

            assertThat(removal.remove("ledger-1-")).containsExactly("ledger-1-1", "ledger-1-2");
            assertThat(removal.staticMembers("")).containsExactly("ledger-0-1");
        }
    }

    @Test
    void a_group_without_static_members_is_left_as_it_is() throws Exception {
        try (var admin = admin()) {
            var removal = new StaticMemberRemoval(admin, GROUP + "-empty");
            assertThat(removal.remove("ledger-1-")).isEmpty();
        }
    }

    /**
     * Static members that stop without leaving the group, as the old ledger's consumers did. They join together: a member
     * joining after one has stopped would wait for it until its session times out.
     */
    private void joinAndStop(String... instanceIds) {
        var consumers = new ArrayList<KafkaConsumer<String, String>>();
        try {
            for (var instanceId : instanceIds) {
                var consumer = new KafkaConsumer<String, String>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, GROUP,
                    ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, instanceId,
                    ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, SESSION_TIMEOUT_MS,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
                consumers.add(consumer);
                consumer.subscribe(List.of(topic));
            }
            // Each consumer is polled on this thread only: it refuses calls from another.
            var deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (consumers.stream().anyMatch(consumer -> consumer.assignment().isEmpty())) {
                assertThat(System.nanoTime()).as("joined within %s", TIMEOUT).isLessThan(deadline);
                consumers.forEach(consumer -> consumer.poll(POLL));
            }
        } finally {
            consumers.forEach(KafkaConsumer::close);
        }
    }

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers()));
    }
}
