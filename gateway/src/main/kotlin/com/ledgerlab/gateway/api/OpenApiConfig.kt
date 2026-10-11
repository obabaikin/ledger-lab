package com.ledgerlab.gateway.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The bank's published API; a copy is kept in gateway/openapi.json and checked against the running app (ADR 0011). */
@Configuration
class OpenApiConfig {

    @Bean
    fun bankApi(): OpenAPI = OpenAPI().info(
        Info().title("Ledger Lab bank API").version("1").description("Transfers and the statement of a closed period."),
    )
}
