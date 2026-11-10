package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.gateway.api.OpenApiConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ObjectNode;

/** Each spec kept in the repository must be exactly what the running gateway serves for its group. */
class OpenApiSpecTest extends IntegrationTest {

    private static final String SERVERS = "servers";

    @Test
    void gateway_openapi_json_is_the_published_API_the_running_gateway_serves() throws IOException {
        assertCommittedSpecMatches(OpenApiConfig.BANK_GROUP, "openapi.json");
    }

    @Test
    @DisplayName("gateway openapi-internal json is the internal API the running gateway serves")
    void gateway_openapi_internal_json_is_the_internal_API_the_running_gateway_serves() throws IOException {
        assertCommittedSpecMatches(OpenApiConfig.INTERNAL_GROUP, "openapi-internal.json");
    }

    @Test
    void the_published_API_has_no_internal_path() {
        assertThat(liveSpec(OpenApiConfig.BANK_GROUP).get("paths").propertyNames()).noneMatch(path -> path.startsWith("/internal"));
    }

    @Test
    @DisplayName("every field of a submission is required, because a missing one is refused")
    void every_field_of_a_submission_is_required_because_a_missing_one_is_refused() {
        var schema = liveSpec(OpenApiConfig.BANK_GROUP).get("components").get("schemas").get("SubmitTransferRequest");

        assertThat(schema.get("required").values()).extracting(JsonNode::asString)
            .containsExactlyInAnyOrderElementsOf(schema.get("properties").propertyNames());
    }

    private JsonNode liveSpec(String group) {
        return objectMapper.readTree(http.getForObject("/v3/api-docs/" + group, String.class));
    }

    private void assertCommittedSpecMatches(String group, String fileName) throws IOException {
        var live = normalized(liveSpec(group));
        var committedFile = Path.of(System.getProperty("openapi.dir"), fileName);
        var committed = Files.exists(committedFile) ? normalized(objectMapper.readTree(committedFile.toFile())) : null;

        if (!live.equals(committed)) {
            var actual = Path.of(System.getProperty("openapi.actualDir"), fileName);
            Files.createDirectories(actual.getParent());
            objectMapper.writer().with(JsonNodeFeature.WRITE_PROPERTIES_SORTED).withDefaultPrettyPrinter().writeValue(actual.toFile(), live);
            assertThat(committed)
                .withFailMessage("The API changed: review, then cp %s %s", actual, committedFile)
                .isEqualTo(live);
        }
    }

    /** The server URL carries the test's random port; it is not part of the contract. */
    private JsonNode normalized(JsonNode spec) {
        var copy = (ObjectNode) spec.deepCopy();
        copy.remove(SERVERS);
        return copy;
    }
}
