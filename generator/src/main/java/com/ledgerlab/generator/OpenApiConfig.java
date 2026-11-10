package com.ledgerlab.generator;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The internal API control calls; a copy is kept in generator/openapi.json and checked against the running app. */
@Configuration
public class OpenApiConfig {

    private static final String NULL_TYPE = "null";

    @Bean
    public OpenAPI generatorApi() {
        return new OpenAPI().info(new Info()
            .title("Ledger Lab generator internal API")
            .version("1")
            .description("Load runs, the client journal and client reconciliation, for control."));
    }

    /** A nullable object is written as "the object or null"; a reference with a type beside it is not valid OpenAPI 3.1. */
    @Bean
    public OpenApiCustomizer nullableReferences() {
        return api -> api.getComponents().getSchemas().values().forEach(OpenApiConfig::writeNullableReferences);
    }

    // The model of the spec hands out its schemas without type arguments.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void writeNullableReferences(Schema schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null) {
            properties.replaceAll((name, property) -> isNullableReference(property) ? objectOrNull(property.get$ref()) : property);
        }
    }

    private static boolean isNullableReference(Schema<?> property) {
        var types = property.getTypes();
        return property.get$ref() != null && types != null && types.contains(NULL_TYPE);
    }

    private static Schema<Object> objectOrNull(String reference) {
        var nothing = new Schema<Object>();
        nothing.addType(NULL_TYPE);
        var objectOrNull = new Schema<Object>();
        objectOrNull.addOneOfItem(new Schema<Object>().$ref(reference));
        objectOrNull.addOneOfItem(nothing);
        return objectOrNull;
    }
}
