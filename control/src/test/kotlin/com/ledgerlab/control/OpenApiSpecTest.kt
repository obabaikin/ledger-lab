package com.ledgerlab.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** control/openapi.json must be exactly what the running control serves; the page's types come from it. */
class OpenApiSpecTest : IntegrationTest() {

    @Test
    fun `control openapi json is the API the running control serves`() {
        val live = normalized(objectMapper.readTree(http.getForObject("/v3/api-docs", String::class.java)))
        val committedFile = Path.of(System.getProperty("openapi.dir"), FILE_NAME)
        val committed = if (Files.exists(committedFile)) normalized(objectMapper.readTree(committedFile.toFile())) else null

        if (live != committed) {
            val actual = Path.of(System.getProperty("openapi.actualDir"), FILE_NAME)
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
        const val FILE_NAME = "openapi.json"
        const val SERVERS = "servers"
    }
}
