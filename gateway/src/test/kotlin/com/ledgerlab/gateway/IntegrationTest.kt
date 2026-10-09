package com.ledgerlab.gateway

import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.common.TransferResultEvent
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.ResponseEntity
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.boot.test.context.TestConfiguration
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class, IntegrationTest.Topics::class)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
abstract class IntegrationTest {

    /** In production topics are created by infrastructure (infra/kafka), not by the services. */
    @TestConfiguration(proxyBeanMethods = false)
    class Topics {
        @Bean
        fun transfersTopic(props: GatewayProperties): NewTopic = NewTopic(props.transfersTopic, 2, 1)

        @Bean
        fun resultsTopic(props: GatewayProperties): NewTopic = NewTopic(props.resultsTopic, 2, 1)
    }

    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var props: GatewayProperties

    protected fun body(transferId: UUID = UUID.randomUUID(), runId: UUID = UUID.randomUUID(), from: Int = 1, to: Int = 2, amount: Long = 100) =
        mapOf("transferId" to transferId, "runId" to runId, "fromAccount" to from, "toAccount" to to, "amountCents" to amount)

    protected fun submit(body: Map<String, Any>, wait: String? = null): ResponseEntity<Map<*, *>> =
        http.postForEntity("/v1/transfers" + (wait?.let { "?wait=$it" } ?: ""), body, Map::class.java)

    protected fun publishResult(runId: UUID, transferId: UUID, outcome: TransferOutcome, reason: String? = null) {
        val event = TransferResultEvent(runId, transferId, outcome, reason, Instant.now())
        kafka.send(props.resultsTopic, event.partitionKey(), objectMapper.writeValueAsString(event)).get()
    }
}
