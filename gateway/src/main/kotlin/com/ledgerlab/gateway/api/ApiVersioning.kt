package com.ledgerlab.gateway.api

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Public endpoints carry the version as the first path segment (/v1/...). Internal and
 * actuator paths are not versioned: they have no external consumers.
 */
@Configuration
class ApiVersioning : WebMvcConfigurer {

    override fun configureApiVersioning(configurer: ApiVersionConfigurer) {
        configurer
            .usePathSegment(0) { path -> path.value().startsWith("/v") }
            // unversioned paths (internal, actuator) must not be rejected for lacking a version
            .setVersionRequired(false)
    }
}
