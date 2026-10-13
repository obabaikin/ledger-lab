package com.ledgerlab.reporting

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

@AutoConfigureTestRestTemplate
class OpenApiSpecTest : IntegrationTest() {

    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `the committed spec matches the running reporting service`() {
        val live = normalized(objectMapper.readTree(http.getForObject("/v3/api-docs", String::class.java)))
        val committedFile = Path.of(System.getProperty("openapi.spec"))
        val committed = if (Files.exists(committedFile)) normalized(objectMapper.readTree(committedFile.toFile())) else null

        if (live != committed) {
            val actual = Path.of(System.getProperty("openapi.actual"))
            Files.createDirectories(actual.parent)
            objectMapper.writer().with(JsonNodeFeature.WRITE_PROPERTIES_SORTED).withDefaultPrettyPrinter().writeValue(actual.toFile(), live)
            assertThat(committed)
                .withFailMessage("The API changed: review, then cp %s %s", actual, committedFile)
                .isEqualTo(live)
        }
    }

    /** The server URL carries the test's random port; it is not part of the contract. */
    private fun normalized(spec: JsonNode): JsonNode = (spec.deepCopy() as ObjectNode).apply { remove(SERVERS) }

    private companion object {
        const val SERVERS = "servers"
    }
}
