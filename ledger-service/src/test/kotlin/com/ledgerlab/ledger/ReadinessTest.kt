package com.ledgerlab.ledger

import com.ledgerlab.ledger.kafka.AssignedPartitionsGauge
import com.ledgerlab.ledger.kafka.KafkaAssignmentHealthIndicator
import com.ledgerlab.ledger.kafka.TransferConsumer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.listener.MessageListenerContainer
import java.time.Duration

@AutoConfigureTestRestTemplate
class ReadinessTest : IntegrationTest() {

    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var listeners: KafkaListenerEndpointRegistry

    private fun readiness(): HttpStatus = HttpStatus.valueOf(http.getForEntity(READINESS, String::class.java).statusCode.value())

    private fun liveness(): HttpStatus = HttpStatus.valueOf(http.getForEntity(LIVENESS, String::class.java).statusCode.value())

    @Test
    fun `not ready while the transfer consumer holds no partition, ready again once it does, without a restart`() {
        val container = checkNotNull(listeners.getListenerContainer(TransferConsumer.LISTENER_ID))
        await().atMost(TIMEOUT).until { readiness() == HttpStatus.OK }

        container.stop()
        try {
            assertThat(readiness()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(liveness()).isEqualTo(HttpStatus.OK)
        } finally {
            container.start()
        }

        await().atMost(TIMEOUT).until { readiness() == HttpStatus.OK }
    }

    @Test
    fun `some of the partitions are enough to be ready`() {
        val container = mock(MessageListenerContainer::class.java)
        `when`(container.assignedPartitions).thenReturn(listOf(TopicPartition("transfers", 0)))
        val registry = mock(KafkaListenerEndpointRegistry::class.java)
        `when`(registry.getListenerContainer(TransferConsumer.LISTENER_ID)).thenReturn(container)

        assertThat(KafkaAssignmentHealthIndicator(registry).health().status).isEqualTo(Status.UP)
    }

    @Test
    fun `the partitions an instance holds are published, so a live but idle instance shows`() {
        val container = mock(MessageListenerContainer::class.java)
        `when`(container.assignedPartitions).thenReturn(listOf(TopicPartition("transfers", 0), TopicPartition("transfers", 2)))
        val listeners = mock(KafkaListenerEndpointRegistry::class.java)
        `when`(listeners.getListenerContainer(TransferConsumer.LISTENER_ID)).thenReturn(container)
        val meters = SimpleMeterRegistry()

        AssignedPartitionsGauge(listeners, meters)

        assertThat(meters.get(AssignedPartitionsGauge.METRIC).gauge().value()).isEqualTo(2.0)
        `when`(container.assignedPartitions).thenReturn(emptyList())
        assertThat(meters.get(AssignedPartitionsGauge.METRIC).gauge().value()).isZero()
    }

    private companion object {
        const val READINESS = "/actuator/health/readiness"
        const val LIVENESS = "/actuator/health/liveness"
        val TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
