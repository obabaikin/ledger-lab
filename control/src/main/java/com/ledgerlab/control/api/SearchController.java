package com.ledgerlab.control.api;

import com.ledgerlab.control.ControlProperties;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Absolute addresses for crawlers, from the deployment's own address: the same image serves any domain. */
@Hidden
@RestController
public class SearchController {

    public static final String SITEMAP = "/sitemap.xml";
    public static final List<String> INDEXED = List.of("/", "/privacy", "/impressum");
    // Invite links and sessions carry tokens; the API and the deletion page are no content.
    public static final List<String> PRIVATE = List.of("/api/", "/s/", "/delete", "/*?ref=");

    private final ControlProperties props;

    public SearchController(ControlProperties props) {
        this.props = props;
    }

    private String base() {
        return props.site().baseUrl().replaceAll("/+$", "");
    }

    @GetMapping(path = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String robots() {
        return "User-agent: *\n"
            + PRIVATE.stream().map(it -> "Disallow: " + it + "\n").collect(Collectors.joining())
            + "Sitemap: " + base() + SITEMAP + "\n";
    }

    @GetMapping(path = SITEMAP, produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemap() {
        return INDEXED.stream()
            .map(it -> "<url><loc>" + base() + it + "</loc></url>")
            .collect(Collectors.joining(
                "", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">", "</urlset>"));
    }
}
