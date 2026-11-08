package com.ledgerlab.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["control.site.version=$DEPLOYED_VERSION"])
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

    @Test
    fun `the site api returns the version it was deployed with`() {
        val site = http.getForEntity("/api/site", Map::class.java).body!!

        assertThat(site["version"]).isEqualTo(DEPLOYED_VERSION)
    }
}

private const val DEPLOYED_VERSION = "v9.8.7-rc.6"
