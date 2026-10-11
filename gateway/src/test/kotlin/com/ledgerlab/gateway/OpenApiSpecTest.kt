package com.ledgerlab.gateway

import com.ledgerlab.gateway.api.OpenApiConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** Each spec kept in the repository must be exactly what the running gateway serves for its group. */
class OpenApiSpecTest : IntegrationTest() {

    @Test
    fun `gateway openapi json is the published API the running gateway serves`() =
        assertCommittedSpecMatches(OpenApiConfig.BANK_GROUP, "openapi.json")

    @Test
    fun `gateway openapi-internal json is the internal API the running gateway serves`() =
        assertCommittedSpecMatches(OpenApiConfig.INTERNAL_GROUP, "openapi-internal.json")

    @Test
    fun `the published API has no internal path`() {
        assertThat(liveSpec(OpenApiConfig.BANK_GROUP).get("paths").propertyNames()).noneMatch { it.startsWith("/internal") }
    }

    @Test
    fun `every field of a submission is required, because a missing one is refused`() {
        val schema = liveSpec(OpenApiConfig.BANK_GROUP).get("components").get("schemas").get("SubmitTransferRequest")

        assertThat(schema.get("required").values().map { it.asString() })
            .containsExactlyInAnyOrderElementsOf(schema.get("properties").propertyNames())
    }

    private fun liveSpec(group: String): JsonNode = objectMapper.readTree(http.getForObject("/v3/api-docs/$group", String::class.java))

    private fun assertCommittedSpecMatches(group: String, fileName: String) {
        val live = normalized(liveSpec(group))
        val committedFile = Path.of(System.getProperty("openapi.dir"), fileName)
        val committed = if (Files.exists(committedFile)) normalized(objectMapper.readTree(committedFile.toFile())) else null

        if (live != committed) {
            val actual = Path.of(System.getProperty("openapi.actualDir"), fileName)
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
