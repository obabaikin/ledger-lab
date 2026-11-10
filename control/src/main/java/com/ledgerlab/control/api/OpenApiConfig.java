package com.ledgerlab.control.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String NULL_TYPE = "null";

    @Bean
    public OpenAPI controlApi() {
        return new OpenAPI().info(new Info()
            .title("Ledger Lab control API")
            .version("1")
            .description("Invite sessions and the run queue, for the control page."));
    }

    /** springdoc does not read the types' nullability: without this every field of the page's types would be optional (ADR 0014). */
    @Bean
    public OpenApiCustomizer everyFieldRequired() {
        return api -> {
            var components = api.getComponents();
            if (components != null && components.getSchemas() != null) {
                components.getSchemas().values().forEach(OpenApiConfig::describeFields);
            }
        };
    }

    // The model of the spec hands out its schemas without type arguments.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void describeFields(Schema schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        schema.setRequired(properties.keySet().stream().sorted().toList());
        // A nullable object is written as "the object or null"; a reference with a type beside it is not valid OpenAPI 3.1.
        properties.replaceAll((name, property) -> isNullableReference(property) ? objectOrNull(property) : property);
    }

    private static boolean isNullableReference(Schema<?> property) {
        var types = property.getTypes();
        return property.get$ref() != null && types != null && types.contains(NULL_TYPE);
    }

    private static Schema<Object> objectOrNull(Schema<?> property) {
        var nothing = new Schema<Object>();
        nothing.addType(NULL_TYPE);
        var objectOrNull = new Schema<Object>();
        objectOrNull.setDescription(property.getDescription());
        objectOrNull.addOneOfItem(new Schema<Object>().$ref(property.get$ref()));
        objectOrNull.addOneOfItem(nothing);
        return objectOrNull;
    }
}
