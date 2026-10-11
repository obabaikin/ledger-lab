package com.ledgerlab.generator

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The internal API control calls; a copy is kept in generator/openapi.json and checked against the running app. */
@Configuration
class OpenApiConfig {

    @Bean
    fun generatorApi(): OpenAPI = OpenAPI().info(
        Info().title("Ledger Lab generator internal API").version("1").description("Load runs, the client journal and client reconciliation, for control."),
    )
}
