package com.ledgerlab.control.api

import com.ledgerlab.control.ControlProperties
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Absolute addresses for crawlers, from the deployment's own address: the same image serves any domain. */
@Hidden
@RestController
class SearchController(private val props: ControlProperties) {

    private val base get() = props.site.baseUrl.trimEnd('/')

    @GetMapping("/robots.txt", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun robots(): String =
        (listOf("User-agent: *") + PRIVATE.map { "Disallow: $it" } + "Sitemap: $base$SITEMAP").joinToString("\n", postfix = "\n")

    @GetMapping(SITEMAP, produces = [MediaType.APPLICATION_XML_VALUE])
    fun sitemap(): String =
        INDEXED.joinToString("", """<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""", "</urlset>") {
            "<url><loc>$base$it</loc></url>"
        }

    companion object {
        const val SITEMAP = "/sitemap.xml"
        val INDEXED = listOf("/", "/privacy", "/impressum")
        // Invite links and sessions carry tokens; the API and the deletion page are no content.
        val PRIVATE = listOf("/api/", "/s/", "/delete", "/*?ref=")
    }
}
