package com.ledgerlab.gateway.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two specs, each kept in the repository and checked against the running app: the bank's published API
 * (gateway/openapi.json, ADR 0011) and the run endpoints control calls (gateway/openapi-internal.json).
 */
@Configuration
public class OpenApiConfig {

    public static final String BANK_GROUP = "bank";
    public static final String INTERNAL_GROUP = "internal";
    private static final String INTERNAL_PATHS = "/internal/**";

    @Bean
    public OpenAPI bankApi() {
        return new OpenAPI().info(new Info()
            .title("Ledger Lab bank API")
            .version(ApiVersioning.VERSION)
            .description("Transfers and the statement of a closed period."));
    }

    @Bean
    public GroupedOpenApi publishedApi() {
        return GroupedOpenApi.builder().group(BANK_GROUP).pathsToExclude(INTERNAL_PATHS).build();
    }

    @Bean
    public GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder()
            .group(INTERNAL_GROUP)
            .pathsToMatch(INTERNAL_PATHS)
            .addOpenApiCustomizer(api -> api.info(new Info()
                .title("Ledger Lab gateway internal API")
                .version(ApiVersioning.VERSION)
                .description("Run summary, period close and run deletion, for control.")))
            .build();
    }
}
