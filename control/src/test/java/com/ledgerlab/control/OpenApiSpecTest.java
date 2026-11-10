package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ObjectNode;

/** control/openapi.json must be exactly what the running control serves; the page's types come from it. */
class OpenApiSpecTest extends IntegrationTest {

    private static final String FILE_NAME = "openapi.json";
    private static final String SERVERS = "servers";
    private static final String JSON = "application/json";
    private static final String RUNS = "/api/sessions/{sessionId}/runs";
    private static final String CHAOS = "/api/sessions/{sessionId}/chaos";
    private static final String SESSIONS = "/api/sessions";

    @Test
    void control_openapi_json_is_the_API_the_running_control_serves() throws Exception {
        var live = normalized(objectMapper.readTree(http.getForObject("/v3/api-docs", String.class)));
        var committedFile = Path.of(System.getProperty("openapi.dir"), FILE_NAME);
        var committed = Files.exists(committedFile) ? normalized(objectMapper.readTree(committedFile.toFile())) : null;

        if (!live.equals(committed)) {
            var actual = Path.of(System.getProperty("openapi.actualDir"), FILE_NAME);
            Files.createDirectories(actual.getParent());
            objectMapper.writer().with(JsonNodeFeature.WRITE_PROPERTIES_SORTED).withDefaultPrettyPrinter().writeValue(actual.toFile(), live);
            assertThat(committed)
                .withFailMessage("The API changed: review, then cp %s %s", actual, committedFile)
                .isEqualTo(live);
        }
    }

    @Test
    void every_error_answer_is_declared_as_JSON_with_its_error_text_required() {
        var spec = committed();
        var errors = new ArrayList<Map.Entry<String, JsonNode>>();
        for (var pathItem : spec.get("paths").properties()) {
            for (var operation : pathItem.getValue().properties()) {
                for (var response : operation.getValue().get("responses").properties()) {
                    if ("45".indexOf(response.getKey().charAt(0)) >= 0) {
                        errors.add(Map.entry(operation.getKey() + " " + pathItem.getKey() + " " + response.getKey(), response.getValue()));
                    }
                }
            }
        }

        assertThat(errors).isNotEmpty();
        errors.forEach(error -> assertThat(List.copyOf(error.getValue().get("content").propertyNames())).as(error.getKey()).containsExactly(JSON));
        assertThat(spec.get("components").get("schemas").get("ApiError").get("required").toString()).isEqualTo("[\"error\"]");
    }

    @Test
    @DisplayName("a refusal to wait declares Retry-After, a created or accepted run or session declares its Location")
    void a_refusal_to_wait_declares_Retry_After_a_created_or_accepted_run_or_session_declares_its_Location() {
        var paths = committed().get("paths");
        BiFunction<String, String, Collection<String>> headersOf = (path, status) -> {
            var headers = paths.get(path).get("post").get("responses").get(status).get("headers");
            return headers == null ? List.of() : headers.propertyNames();
        };

        assertThat(headersOf.apply(RUNS, "429")).contains(HttpHeaders.RETRY_AFTER);
        assertThat(headersOf.apply(CHAOS, "429")).contains(HttpHeaders.RETRY_AFTER);
        assertThat(headersOf.apply(RUNS, "202")).contains(HttpHeaders.LOCATION);
        assertThat(headersOf.apply(RUNS, "200")).contains(HttpHeaders.LOCATION);
        assertThat(headersOf.apply(SESSIONS, "201")).contains(HttpHeaders.LOCATION);
    }

    private JsonNode committed() {
        return objectMapper.readTree(Path.of(System.getProperty("openapi.dir"), FILE_NAME).toFile());
    }

    /** The server URL carries the test's random port; it is not part of the contract. */
    private JsonNode normalized(JsonNode spec) {
        var copy = (ObjectNode) spec.deepCopy();
        copy.remove(SERVERS);
        return copy;
    }
}
