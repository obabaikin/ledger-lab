package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ControlApplicationTest extends IntegrationTest {

    @Test
    void starts_on_its_own_schema_and_reports_healthy() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT to_regclass('control.runs') IS NOT NULL").query(Boolean.class).single()).isTrue();
    }
}
