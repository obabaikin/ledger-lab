package com.ledgerlab.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(ControlApplicationTest.Containers::class)
class ControlApplicationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class Containers {
        @Bean
        @ServiceConnection
        fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")
    }

    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `starts on its own schema and reports healthy`() {
        assertThat(http.getForEntity("/actuator/health", String::class.java).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(jdbc.queryForObject("SELECT to_regclass('control.flyway_schema_history') IS NOT NULL", Boolean::class.java)).isTrue()
    }
}
