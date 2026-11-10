package com.ledgerlab.gateway.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Public endpoints carry the version as the first path segment (/v1/...). Internal and
 * actuator paths are not versioned: they have no external consumers.
 */
@Configuration
public class ApiVersioning implements WebMvcConfigurer {

    public static final String VERSION = "1";
    public static final String PATH_VERSION = "v" + VERSION;

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
            .usePathSegment(0, path -> path.value().startsWith("/v"))
            .setVersionRequired(false);
    }
}
