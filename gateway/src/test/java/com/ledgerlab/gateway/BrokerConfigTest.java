package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;

class BrokerConfigTest extends IntegrationTest {

    @Autowired KafkaAdmin kafkaAdmin;

    @Test
    @DisplayName("the test broker does not create topics on demand, like the real one")
    void the_test_broker_does_not_create_topics_on_demand_like_the_real_one() throws Exception {
        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            var brokerId = admin.describeCluster().nodes().get().iterator().next().idString();
            var broker = new ConfigResource(ConfigResource.Type.BROKER, brokerId);
            var config = admin.describeConfigs(List.of(broker)).all().get().get(broker);

            assertThat(config.get("auto.create.topics.enable").value()).isEqualTo("false");
        }
    }
}
