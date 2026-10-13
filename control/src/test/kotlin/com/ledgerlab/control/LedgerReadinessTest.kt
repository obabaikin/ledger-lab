package com.ledgerlab.control

import com.ledgerlab.control.RunFixtures.prometheus
import com.ledgerlab.control.ledger.LedgerReadiness
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class LedgerReadinessTest : IntegrationTest() {

    @Autowired lateinit var readiness: LedgerReadiness

    private fun held(partitions: Int) =
        prometheus.answerQuery("GET", QUERY_PATH, "query=$HELD", 200, prometheus(listOf(emptyMap<String, String>() to partitions)))

    @Test
    fun `ready when the live instances hold every partition`() {
        held(props.ledger.transferPartitions)

        assertThat(readiness.isReady()).isTrue()
    }

    @Test
    fun `not ready while a killed instance still holds its partitions, though the live ones hold some`() {
        held(props.ledger.transferPartitions - 2)

        assertThat(readiness.isReady()).isFalse()
    }

    @Test
    fun `not ready when more partitions are counted than there are, as in a rebalance between scrapes`() {
        held(props.ledger.transferPartitions + 2)

        assertThat(readiness.isReady()).isFalse()
    }

    @Test
    fun `not ready when no ledger instance is scraped`() {
        prometheus.answerQuery("GET", QUERY_PATH, "query=$HELD", 200, prometheus(emptyList()))

        assertThat(readiness.isReady()).isFalse()
    }

    @Test
    fun `not ready when Prometheus cannot be read`() {
        prometheus.status = 503

        assertThat(readiness.isReady()).isFalse()
    }

    private companion object {
        const val QUERY_PATH = "/api/v1/query"
        const val HELD = "sum(ledger_kafka_assigned_partitions)"
    }
}
