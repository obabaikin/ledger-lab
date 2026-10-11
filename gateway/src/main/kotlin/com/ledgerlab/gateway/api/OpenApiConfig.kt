package com.ledgerlab.gateway.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Two specs, each kept in the repository and checked against the running app: the bank's published API
 * (gateway/openapi.json, ADR 0011) and the run endpoints control calls (gateway/openapi-internal.json).
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun bankApi(): OpenAPI = OpenAPI().info(
        Info().title("Ledger Lab bank API").version("1").description("Transfers and the statement of a closed period."),
    )

    @Bean
    fun publishedApi(): GroupedOpenApi = GroupedOpenApi.builder().group(BANK_GROUP).pathsToExclude(INTERNAL_PATHS).build()

    @Bean
    fun internalApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group(INTERNAL_GROUP)
        .pathsToMatch(INTERNAL_PATHS)
        .addOpenApiCustomizer { api ->
            api.info(Info().title("Ledger Lab gateway internal API").version("1").description("Run summary, period close and run deletion, for control."))
        }
        .build()

    companion object {
        const val BANK_GROUP = "bank"
        const val INTERNAL_GROUP = "internal"
        private const val INTERNAL_PATHS = "/internal/**"
    }
}
