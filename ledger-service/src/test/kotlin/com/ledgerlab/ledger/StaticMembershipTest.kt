package com.ledgerlab.ledger

import org.apache.kafka.clients.admin.Admin
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.test.context.TestPropertySource
import java.time.Duration

@TestPropertySource(properties = ["ledger.consumer.instance-id=$INSTANCE", "spring.kafka.listener.concurrency=2"])
class StaticMembershipTest : IntegrationTest() {

    @Autowired lateinit var kafka: KafkaProperties
    @Autowired lateinit var kafkaAdmin: KafkaAdmin

    @Test
    fun `each consumer of an instance joins the group under the instance's name with its own suffix`() {
        Admin.create(kafkaAdmin.configurationProperties).use { admin ->
            await().atMost(TIMEOUT).untilAsserted {
                val members = admin.describeConsumerGroups(listOf(kafka.consumer.groupId)).all().get().values.single().members()
                assertThat(members.map { it.groupInstanceId().orElse(null) }).containsExactlyInAnyOrder("$INSTANCE-0", "$INSTANCE-1")
            }
        }
    }
}

private const val INSTANCE = "ledger-test"
private val TIMEOUT: Duration = Duration.ofSeconds(60)
