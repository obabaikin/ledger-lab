package com.ledgerlab.reporting.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The internal API control calls; a copy is kept in reporting/openapi.json and checked against the running app. */
@Configuration
class OpenApiConfig {

    @Bean
    fun reportingApi(): OpenAPI = OpenAPI().info(
        Info().title("Ledger Lab reporting internal API").version("1").description("A run's numbers from the bank's events, for control."),
    )
}
