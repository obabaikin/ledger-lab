package com.ledgerlab.control.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun controlApi(): OpenAPI = OpenAPI().info(
        Info().title("Ledger Lab control API").version("1").description("Invite sessions and the run queue, for the control page."),
    )

    /** springdoc does not read Kotlin's nullability: without this every field of the page's types would be optional (ADR 0014). */
    @Bean
    fun everyFieldRequired() = OpenApiCustomizer { api ->
        api.components?.schemas?.values?.forEach { schema ->
            schema.properties?.keys?.let { schema.required = it.sorted() }
        }
    }
}
