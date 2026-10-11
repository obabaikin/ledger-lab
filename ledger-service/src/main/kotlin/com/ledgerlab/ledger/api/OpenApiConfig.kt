package com.ledgerlab.ledger.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The internal API control calls; a copy is kept in ledger-service/openapi.json and checked against the running app. */
@Configuration
class OpenApiConfig {

    @Bean
    fun ledgerApi(): OpenAPI = OpenAPI().info(
        Info().title("Ledger Lab ledger internal API").version("1").description("A run's accounts, the ledger's reconciliation and run deletion, for control."),
    )
}
