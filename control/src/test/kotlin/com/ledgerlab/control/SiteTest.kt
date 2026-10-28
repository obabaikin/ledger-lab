package com.ledgerlab.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SiteTest : IntegrationTest() {

    @Test
    fun `the page gets the link's terms from the configuration, and no operator or site key where none is set`() {
        val site = http.getForEntity("/api/site", Map::class.java).body!!

        assertThat(site["linkDays"]).isEqualTo(7)
        assertThat(site["retentionDays"]).isEqualTo(30)
        assertThat(site["linksPerDay"]).isEqualTo(3)
        assertThat(site["turnstileSiteKey"]).isNull()
        assertThat(site.containsKey("operator")).isTrue()
        assertThat(site["operator"]).isNull()
    }
}
