package com.ledgerlab.ledger

import com.ledgerlab.ledger.chaos.CrashEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.context.ApplicationContext

@AutoConfigureTestRestTemplate
class CrashEndpointAbsentTest : IntegrationTest() {

    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var context: ApplicationContext

    // Checked without a POST: were the endpoint there, a POST would halt the test JVM.
    @Test
    fun `crash endpoint is absent unless chaos is enabled`() {
        val links = (http.getForEntity("/actuator", Map::class.java).body!!["_links"] as Map<*, *>).keys

        assertThat(links).contains("health").doesNotContain(CrashEndpoint.ID)
        assertThat(context.getBeanNamesForType(CrashEndpoint::class.java)).isEmpty()
    }
}
