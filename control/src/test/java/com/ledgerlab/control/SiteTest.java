package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "control.site.version=" + SiteTest.DEPLOYED_VERSION)
class SiteTest extends IntegrationTest {

    static final String DEPLOYED_VERSION = "v9.8.7-rc.6";

    @Test
    @DisplayName("the page gets the link's terms from the configuration, and no operator or site key where none is set")
    void the_page_gets_the_links_terms_from_the_configuration_and_no_operator_or_site_key_where_none_is_set() {
        var site = get("/api/site").getBody();

        assertThat(site.get("linkDays")).isEqualTo(7);
        assertThat(site.get("retentionDays")).isEqualTo(30);
        assertThat(site.get("linksPerDay")).isEqualTo(3);
        assertThat(site.get("turnstileSiteKey")).isNull();
        assertThat(site.containsKey("operator")).isTrue();
        assertThat(site.get("operator")).isNull();
    }

    @Test
    void the_site_api_returns_the_version_it_was_deployed_with() {
        var site = get("/api/site").getBody();

        assertThat(site.get("version")).isEqualTo(DEPLOYED_VERSION);
    }
}
