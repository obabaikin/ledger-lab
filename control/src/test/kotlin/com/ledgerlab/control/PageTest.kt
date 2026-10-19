package com.ledgerlab.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.UUID

class PageTest : IntegrationTest() {

    @Test
    fun `the page is served at the root and at a session's address`() {
        val root = http.getForEntity("/", String::class.java)
        val session = http.getForEntity("/s/${UUID.randomUUID()}", String::class.java)

        assertThat(root.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(root.headers.contentType?.isCompatibleWith(MediaType.TEXT_HTML)).isTrue()
        assertThat(root.body).contains("<div id=\"root\"></div>")
        assertThat(session.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(session.body).isEqualTo(root.body)
    }

    @Test
    fun `the browser asks again for the page on every load, so a deploy is seen at once`() {
        assertThat(http.getForEntity("/", String::class.java).headers.cacheControl).contains("no-cache")
    }

    @Test
    fun `a reload gets the page itself, not a 304 that keeps the one of an earlier build`() {
        val first = http.getForEntity("/", String::class.java)
        val headers = HttpHeaders().apply {
            first.headers.lastModified.takeIf { it > 0 }?.let { ifModifiedSince = it }
            first.headers.eTag?.let { setIfNoneMatch(it) }
            if (isEmpty()) ifModifiedSince = java.time.Instant.parse("1980-02-01T00:00:00Z").toEpochMilli()
        }
        val reload = http.exchange("/", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        assertThat(reload.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(reload.body).isEqualTo(first.body)
    }

    @Test
    fun `the page's scripts are served with it`() {
        val script = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(http.getForObject("/", String::class.java)!!)!!.groupValues[1]

        assertThat(http.getForEntity(script, String::class.java).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `an address that is neither the page nor the API is not found`() {
        assertThat(http.getForEntity("/nothing-here", String::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a browser that asks for an address the page does not have is sent to the start`() {
        listOf("/s", "/s/", "/nothing-here", "/%D0%B5%D0%BA%D0%BE").forEach { path ->
            val answer = asBrowser(path)

            assertThat(answer.statusCode).describedAs(path).isEqualTo(HttpStatus.FOUND)
            assertThat(answer.headers.location?.path).describedAs(path).isEqualTo("/")
        }
    }

    @Test
    fun `a session's address with something after it is sent to the session`() {
        val session = "/s/${UUID.randomUUID()}"
        listOf("$session/", "$session/more").forEach { path ->
            assertThat(asBrowser(path).headers.location?.path).describedAs(path).isEqualTo(session)
        }
    }

    @Test
    fun `the redirect keeps the invite in the query`() {
        val answer = asBrowser("/index?ref=look-test")

        assertThat(answer.headers.location?.path).isEqualTo("/")
        assertThat(answer.headers.location?.query).isEqualTo("ref=look-test")
    }

    @Test
    fun `a browser that asks for an unknown API address still gets the API's 404`() {
        assertThat(asBrowser("/api/nothing-here").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun asBrowser(path: String) = http.withRedirects(HttpRedirects.DONT_FOLLOW)
        .exchange(path, HttpMethod.GET, HttpEntity<Void>(HttpHeaders().apply { accept = listOf(MediaType.TEXT_HTML) }), String::class.java)
}
