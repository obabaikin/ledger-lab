package com.ledgerlab.ledger

import com.ledgerlab.ledger.metrics.RunDuplicates
import com.ledgerlab.ledger.metrics.RunDuplicatesProperties
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class RunDuplicatesTest {

    private fun MeterRegistry.duplicates(runId: UUID): Double? =
        find(RunDuplicates.METRIC).tag(RunDuplicates.RUN_TAG, runId.toString()).counter()?.count()

    @Test
    fun `duplicates are counted per run`() {
        val registry = SimpleMeterRegistry()
        val duplicates = RunDuplicates(registry, RunDuplicatesProperties(Duration.ofMinutes(10)))
        val (a, b) = UUID.randomUUID() to UUID.randomUUID()

        repeat(3) { duplicates.record(a) }
        duplicates.record(b)

        assertThat(registry.duplicates(a)).isEqualTo(3.0)
        assertThat(registry.duplicates(b)).isEqualTo(1.0)
    }

    @Test
    fun `an idle run's counter is removed and a later duplicate starts a new one`() {
        val registry = SimpleMeterRegistry()
        val duplicates = RunDuplicates(registry, RunDuplicatesProperties(Duration.ZERO))
        val runId = UUID.randomUUID()
        duplicates.record(runId)

        duplicates.removeIdle()

        assertThat(registry.duplicates(runId)).isNull()
        duplicates.record(runId)
        assertThat(registry.duplicates(runId)).isEqualTo(1.0)
    }

    @Test
    fun `a failing registry does not fail the transfer that was a duplicate`() {
        val broken = object : SimpleMeterRegistry() {
            override fun newCounter(id: Meter.Id) = throw IllegalStateException("registry closed")
        }
        val duplicates = RunDuplicates(broken, RunDuplicatesProperties(Duration.ofMinutes(10)))

        assertThatCode { duplicates.record(UUID.randomUUID()) }.doesNotThrowAnyException()
    }
}
