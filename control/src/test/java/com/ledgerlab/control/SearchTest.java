package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.api.SearchController;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "control.site.base-url=" + SearchTest.SITE + "/")
class SearchTest extends IntegrationTest {

    static final String SITE = "https://site.example";

    @Test
    @DisplayName("robots keep crawlers out of tokens and the api and point to the sitemap at the deployment's address")
    void robots_keep_crawlers_out_of_tokens_and_the_api_and_point_to_the_sitemap_at_the_deployments_address() {
        var robots = http.getForEntity("/robots.txt", String.class);

        assertThat(robots.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(robots.getBody().lines().toList()).contains("User-agent: *", "Sitemap: " + SITE + SearchController.SITEMAP)
            .containsAll(SearchController.PRIVATE.stream().map(it -> "Disallow: " + it).toList());
    }

    @Test
    @DisplayName("the sitemap lists the public pages at the deployment's address")
    void the_sitemap_lists_the_public_pages_at_the_deployments_address() {
        var sitemap = http.getForEntity(SearchController.SITEMAP, String.class);

        assertThat(sitemap.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Pattern.compile("<loc>([^<]+)</loc>").matcher(sitemap.getBody()).results().map(it -> it.group(1)).toList())
            .containsExactly(SITE + "/", SITE + "/privacy", SITE + "/impressum");
    }
}
