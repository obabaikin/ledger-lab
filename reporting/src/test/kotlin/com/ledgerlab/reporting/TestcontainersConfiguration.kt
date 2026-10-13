package com.ledgerlab.reporting

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

    @Bean
    @ServiceConnection
    // As on the real broker (ADR 0008): a topic the test did not declare is an error, not a new topic.
    fun kafka(): KafkaContainer = KafkaContainer("apache/kafka:4.2.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")

    /** In production topics are created by infrastructure (infra/kafka), not by the services. */
    @Bean
    fun transfersTopic(props: ReportingProperties): NewTopic = NewTopic(props.transfersTopic, 4, 1)

    @Bean
    fun resultsTopic(props: ReportingProperties): NewTopic = NewTopic(props.resultsTopic, 2, 1)
}
