package com.ledgerlab.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** gateway/openapi.json is the bank's published contract; it must be exactly what the running gateway serves. */
class OpenApiSpecTest : IntegrationTest() {

    @Test
    fun `the committed spec matches the running gateway`() {
        val live = normalized(objectMapper.readTree(http.getForObject("/v3/api-docs", String::class.java)))
        val committedFile = Path.of(System.getProperty("openapi.spec"))
        val committed = if (Files.exists(committedFile)) normalized(objectMapper.readTree(committedFile.toFile())) else null

        if (live != committed) {
            val actual = Path.of(System.getProperty("openapi.actual"))
            Files.createDirectories(actual.parent)
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(actual.toFile(), live)
            assertThat(committed)
                .withFailMessage("The API changed: review, then cp %s %s", actual, committedFile)
                .isEqualTo(live)
        }
    }

    @Test
    fun `every field of a submission is required, because a missing one is refused`() {
        val schema = objectMapper.readTree(http.getForObject("/v3/api-docs", String::class.java))
            .get("components").get("schemas").get("SubmitTransferRequest")

        assertThat(schema.get("required").values().map { it.asString() })
            .containsExactlyInAnyOrderElementsOf(schema.get("properties").propertyNames())
    }

    /** The server URL carries the test's random port; it is not part of the contract. */
    private fun normalized(spec: JsonNode): JsonNode = (spec.deepCopy() as ObjectNode).apply { remove(SERVERS) }

    private companion object {
        const val SERVERS = "servers"
    }
}
