package com.ledgerlab.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
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

    @Test
    fun `every error answer is declared as JSON with its error text required`() {
        val spec = committed()
        val errors = spec["paths"].properties().flatMap { (path, item) ->
            item.properties().flatMap { (method, operation) ->
                operation["responses"].properties().filter { it.key.first() in "45" }.map { "$method $path ${it.key}" to it.value }
            }
        }

        assertThat(errors).isNotEmpty
        errors.forEach { (name, response) -> assertThat(response["content"].propertyNames().toList()).`as`(name).containsExactly(JSON) }
        assertThat(spec["components"]["schemas"]["ApiError"]["required"].toString()).isEqualTo("""["error"]""")
    }

    @Test
    fun `a refusal to wait declares Retry-After, a created or accepted run or session declares its Location`() {
        val paths = committed()["paths"]
        val headersOf = { path: String, status: String -> paths[path]["post"]["responses"][status]["headers"]?.propertyNames().orEmpty() }

        assertThat(headersOf(RUNS, "429")).contains(HttpHeaders.RETRY_AFTER)
        assertThat(headersOf(CHAOS, "429")).contains(HttpHeaders.RETRY_AFTER)
        assertThat(headersOf(RUNS, "202")).contains(HttpHeaders.LOCATION)
        assertThat(headersOf(RUNS, "200")).contains(HttpHeaders.LOCATION)
        assertThat(headersOf(SESSIONS, "201")).contains(HttpHeaders.LOCATION)
    }

    private fun committed(): JsonNode = objectMapper.readTree(Path.of(System.getProperty("openapi.dir"), FILE_NAME).toFile())

    /** The server URL carries the test's random port; it is not part of the contract. */
    private fun normalized(spec: JsonNode): JsonNode = (spec.deepCopy() as ObjectNode).apply { remove(SERVERS) }

    private companion object {
        const val FILE_NAME = "openapi.json"
        const val SERVERS = "servers"
        const val JSON = "application/json"
        const val RUNS = "/api/sessions/{sessionId}/runs"
        const val CHAOS = "/api/sessions/{sessionId}/chaos"
        const val SESSIONS = "/api/sessions"
    }
}
