package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureTestRestTemplate
class OpenApiSpecTest extends IntegrationTest {

    private static final String SERVERS = "servers";

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper objectMapper;

    @Test
    void the_committed_spec_matches_the_running_reporting_service() throws IOException {
        var live = normalized(objectMapper.readTree(http.getForObject("/v3/api-docs", String.class)));
        var committedFile = Path.of(System.getProperty("openapi.spec"));
        var committed = Files.exists(committedFile) ? normalized(objectMapper.readTree(committedFile.toFile())) : null;

        if (!live.equals(committed)) {
            var actual = Path.of(System.getProperty("openapi.actual"));
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
