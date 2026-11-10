package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"ledger.consumer.instance-id=" + StaticMembershipTest.INSTANCE, "spring.kafka.listener.concurrency=2"})
class StaticMembershipTest extends IntegrationTest {

    static final String INSTANCE = "ledger-test";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired KafkaProperties kafka;
    @Autowired KafkaAdmin kafkaAdmin;

    @Test
    @DisplayName("each consumer of an instance joins the group under the instance's name with its own suffix")
    void each_consumer_of_an_instance_joins_the_group_under_the_instances_name_with_its_own_suffix() {
        try (var admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            await().atMost(TIMEOUT).untilAsserted(() -> {
                var groups = admin.describeConsumerGroups(List.of(kafka.getConsumer().getGroupId())).all().get().values();
                assertThat(groups).singleElement().satisfies(group ->
                    assertThat(group.members()).extracting(member -> member.groupInstanceId().orElse(null))
                        .containsExactlyInAnyOrder(INSTANCE + "-0", INSTANCE + "-1"));
            });
        }
    }
}
