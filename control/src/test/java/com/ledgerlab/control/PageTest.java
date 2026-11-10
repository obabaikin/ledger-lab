package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class PageTest extends IntegrationTest {

    @Test
    @DisplayName("the page is served at the root and at a session's address")
    void the_page_is_served_at_the_root_and_at_a_sessions_address() {
        var root = http.getForEntity("/", String.class);
        var session = http.getForEntity("/s/" + UUID.randomUUID(), String.class);

        assertThat(root.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(root.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(root.getBody()).contains("<div id=\"root\">");
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(session.getBody()).isEqualTo(root.getBody());
    }

    @Test
    void the_privacy_and_imprint_pages_are_the_same_document() {
        var root = http.getForObject("/", String.class);

        List.of("/privacy", "/impressum").forEach(path -> {
            var page = http.getForEntity(path, String.class);
            assertThat(page.getStatusCode()).describedAs(path).isEqualTo(HttpStatus.OK);
            assertThat(page.getBody()).describedAs(path).isEqualTo(root);
        });
    }

    @Test
    @DisplayName("the browser asks again for the page on every load, so a deploy is seen at once")
    void the_browser_asks_again_for_the_page_on_every_load_so_a_deploy_is_seen_at_once() {
        assertThat(http.getForEntity("/", String.class).getHeaders().getCacheControl()).contains("no-cache");
    }

    @Test
    @DisplayName("a reload gets the page itself, not a 304 that keeps the one of an earlier build")
    void a_reload_gets_the_page_itself_not_a_304_that_keeps_the_one_of_an_earlier_build() {
        var first = http.getForEntity("/", String.class);
        var headers = new HttpHeaders();
        if (first.getHeaders().getLastModified() > 0) {
            headers.setIfModifiedSince(first.getHeaders().getLastModified());
        }
        if (first.getHeaders().getETag() != null) {
            headers.setIfNoneMatch(first.getHeaders().getETag());
        }
        if (headers.isEmpty()) {
            headers.setIfModifiedSince(Instant.parse("1980-02-01T00:00:00Z").toEpochMilli());
        }
        var reload = http.exchange("/", HttpMethod.GET, new HttpEntity<Void>(headers), String.class);

        assertThat(reload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reload.getBody()).isEqualTo(first.getBody());
    }

    @Test
    @DisplayName("the page's scripts are served with it")
    void the_pages_scripts_are_served_with_it() {
        var script = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(http.getForObject("/", String.class)).results().findFirst().orElseThrow().group(1);

        assertThat(http.getForEntity(script, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void an_address_that_is_neither_the_page_nor_the_API_is_not_found() {
        assertThat(http.getForEntity("/nothing-here", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void a_browser_that_asks_for_an_address_the_page_does_not_have_is_sent_to_the_start() {
        List.of("/s", "/s/", "/nothing-here", "/%D0%B5%D0%BA%D0%BE").forEach(path -> {
            var answer = asBrowser(path);

            assertThat(answer.getStatusCode()).describedAs(path).isEqualTo(HttpStatus.FOUND);
            assertThat(answer.getHeaders().getLocation().getPath()).describedAs(path).isEqualTo("/");
        });
    }

    @Test
    @DisplayName("a session's address with something after it is sent to the session")
    void a_sessions_address_with_something_after_it_is_sent_to_the_session() {
        var session = "/s/" + UUID.randomUUID();
        List.of(session + "/", session + "/more").forEach(path ->
            assertThat(asBrowser(path).getHeaders().getLocation().getPath()).describedAs(path).isEqualTo(session));
    }

    @Test
    void the_redirect_keeps_the_invite_in_the_query() {
        var answer = asBrowser("/index?ref=look-test");

        assertThat(answer.getHeaders().getLocation().getPath()).isEqualTo("/");
        assertThat(answer.getHeaders().getLocation().getQuery()).isEqualTo("ref=look-test");
    }

    @Test
    @DisplayName("a browser that asks for an unknown API address still gets the API's 404")
    void a_browser_that_asks_for_an_unknown_API_address_still_gets_the_APIs_404() {
        List.of("/api", "/api/nothing-here").forEach(path ->
            assertThat(asBrowser(path).getStatusCode()).describedAs(path).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void an_encoded_query_is_passed_on_as_it_came() {
        var answer = asBrowser("/nothing-here?ref={ref}", "{x}");

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(answer.getHeaders().getLocation().getRawQuery()).isEqualTo("ref=%7Bx%7D");
    }

    private ResponseEntity<String> asBrowser(String path, String... uriVariables) {
        var headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_HTML));
        return http.withRedirects(HttpRedirects.DONT_FOLLOW)
            .exchange(path, HttpMethod.GET, new HttpEntity<Void>(headers), String.class, (Object[]) uriVariables);
    }
}
