package com.ledgerlab.gateway

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.common.config.ConfigResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaAdmin

class BrokerConfigTest : IntegrationTest() {

    @Autowired lateinit var kafkaAdmin: KafkaAdmin

    @Test
    fun `the test broker does not create topics on demand, like the real one`() {
        AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
            val brokerId = admin.describeCluster().nodes().get().first().idString()
            val broker = ConfigResource(ConfigResource.Type.BROKER, brokerId)
            val config = admin.describeConfigs(listOf(broker)).all().get().getValue(broker)

            assertThat(config.get("auto.create.topics.enable").value()).isEqualTo("false")
        }
    }
}
