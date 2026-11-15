package com.ledgerlab.ledger.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The internal API control calls; a copy is kept in ledger-service/openapi.json and checked against the running app. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerApi() {
        return new OpenAPI().info(new Info()
            .title("Ledger Lab ledger internal API")
            .version("1")
            .description("The ledger's view of a period and of the bank's books, for control."));
    }
}
