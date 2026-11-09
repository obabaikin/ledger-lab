package com.ledgerlab.control

import com.ledgerlab.control.api.SearchController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["control.site.base-url=$SITE/"])
class SearchTest : IntegrationTest() {

    @Test
    fun `robots keep crawlers out of tokens and the api and point to the sitemap at the deployment's address`() {
        val robots = http.getForEntity("/robots.txt", String::class.java)

        assertThat(robots.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(robots.body!!.lines()).contains("User-agent: *", "Sitemap: $SITE${SearchController.SITEMAP}")
            .containsAll(SearchController.PRIVATE.map { "Disallow: $it" })
    }

    @Test
    fun `the sitemap lists the public pages at the deployment's address`() {
        val sitemap = http.getForEntity(SearchController.SITEMAP, String::class.java)

        assertThat(sitemap.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(Regex("<loc>([^<]+)</loc>").findAll(sitemap.body!!).map { it.groupValues[1] }.toList())
            .containsExactly("$SITE/", "$SITE/privacy", "$SITE/impressum")
    }
}

private const val SITE = "https://site.example"
