package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class GeneratorPropertiesTest {

    @Test
    void a_work_mem_below_postgres_minimum_stops_the_start() {
        var belowMinimum = GeneratorProperties.Reconciliation.MIN_WORK_MEM.toBytes() - 1;

        assertThatThrownBy(() -> new GeneratorProperties.Reconciliation(Duration.ofSeconds(60), DataSize.ofBytes(belowMinimum), 20, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("work-mem");
    }
}
