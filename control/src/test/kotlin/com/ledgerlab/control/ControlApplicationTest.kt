package com.ledgerlab.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ControlApplicationTest : IntegrationTest() {

    @Test
    fun `starts on its own schema and reports healthy`() {
        assertThat(http.getForEntity("/actuator/health", String::class.java).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(jdbc.sql("SELECT to_regclass('control.runs') IS NOT NULL").query(Boolean::class.java).single()).isTrue()
    }
}
