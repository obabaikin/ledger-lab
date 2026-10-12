package com.ledgerlab.control

import com.ledgerlab.control.gateway.ClosedPeriod
import com.ledgerlab.control.gateway.GatewayApi
import com.ledgerlab.control.gateway.RunSummary
import com.ledgerlab.control.generator.ClientReconciliationReport
import com.ledgerlab.control.generator.GeneratorApi
import com.ledgerlab.control.generator.JournalTotals
import com.ledgerlab.control.generator.ReconciliationBreaks
import com.ledgerlab.control.generator.SimulationState
import com.ledgerlab.control.generator.SimulationStatus
import com.ledgerlab.control.generator.StartSimulationRequest
import com.ledgerlab.control.generator.StatementTotals
import com.ledgerlab.control.generator.TransferIds
import com.ledgerlab.control.generator.UnknownResolved
import com.ledgerlab.control.ledger.LedgerApi
import com.ledgerlab.control.ledger.OpenAccountsRequest
import com.ledgerlab.control.ledger.ReconciliationCheck
import com.ledgerlab.control.ledger.ReconciliationReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/** Control's models of the ledger, gateway and generator against the specs those services serve (ADR 0011). */
class ServiceContractsTest {

    private class Spec(property: String) {
        val root: JsonNode = jacksonObjectMapper().readTree(File(System.getProperty(property)))
        val name: String = property

        fun operation(method: HttpMethod, path: String): JsonNode =
            checkNotNull(root.get("paths").get(path)?.get(method.name().lowercase())) { "no $method $path in $name" }

        fun schemaOf(type: KClass<*>): JsonNode =
            checkNotNull(root.get("components").get("schemas").get(type.simpleName)) { "no schema ${type.simpleName} in $name" }
    }

    private val ledger = Spec("ledger.spec")
    private val gateway = Spec("gateway.spec")
    private val generator = Spec("generator.spec")

    private companion object {
        const val NULL_TYPE = "null"
    }

    private fun fieldsOf(type: KClass<*>): List<String> = type.primaryConstructor!!.parameters.map { it.name!! }

    private fun JsonNode.names(): List<String> = values().map { it.asString() }

    private fun pathParameters(path: String): List<String> = Regex("\\{(\\w+)}").findAll(path).map { it.groupValues[1] }.toList()

    @Test
    fun `every call control makes is in the spec of its service, with its path parameters`() {
        val calls = listOf(
            Triple(ledger, HttpMethod.POST, LedgerApi.ACCOUNTS_PATH),
            Triple(ledger, HttpMethod.GET, LedgerApi.RECONCILIATION_PATH),
            Triple(ledger, HttpMethod.DELETE, LedgerApi.RUN_PATH),
            Triple(gateway, HttpMethod.GET, GatewayApi.SUMMARY_PATH),
            Triple(gateway, HttpMethod.POST, GatewayApi.CLOSE_PATH),
            Triple(gateway, HttpMethod.DELETE, GatewayApi.RUN_PATH),
            Triple(generator, HttpMethod.POST, GeneratorApi.SIMULATIONS_PATH),
            Triple(generator, HttpMethod.GET, GeneratorApi.SIMULATION_PATH),
            Triple(generator, HttpMethod.POST, GeneratorApi.STOP_PATH),
            Triple(generator, HttpMethod.GET, GeneratorApi.RECONCILIATION_PATH),
        )
        calls.forEach { (spec, method, path) ->
            val parameters = spec.operation(method, path).get("parameters")?.values()?.map { it.get("name").asString() }.orEmpty()
            assertThat(parameters).`as`("$method $path").containsAll(pathParameters(path))
        }
    }

    @Test
    fun `every field control sends or reads is in the spec`() {
        val models = mapOf(
            ledger to listOf(OpenAccountsRequest::class, ReconciliationReport::class),
            gateway to listOf(RunSummary::class, ClosedPeriod::class),
            generator to listOf(
                StartSimulationRequest::class, SimulationStatus::class, ClientReconciliationReport::class, JournalTotals::class,
                StatementTotals::class, ReconciliationBreaks::class, TransferIds::class, UnknownResolved::class,
            ),
        )
        models.forEach { (spec, types) ->
            types.forEach { type ->
                assertThat(spec.schemaOf(type).get("properties").propertyNames()).`as`(type.simpleName).containsAll(fieldsOf(type))
            }
        }
    }

    @Test
    fun `every answer control tells apart is a code the service declares`() {
        val answers = listOf(
            Triple(ledger, HttpMethod.POST to LedgerApi.ACCOUNTS_PATH, listOf("201", "400", "409")),
            Triple(ledger, HttpMethod.GET to LedgerApi.RECONCILIATION_PATH, listOf("200")),
            Triple(ledger, HttpMethod.DELETE to LedgerApi.RUN_PATH, listOf("204", "404")),
            Triple(gateway, HttpMethod.GET to GatewayApi.SUMMARY_PATH, listOf("200")),
            Triple(gateway, HttpMethod.POST to GatewayApi.CLOSE_PATH, listOf("200")),
            Triple(gateway, HttpMethod.DELETE to GatewayApi.RUN_PATH, listOf("204", "404")),
            Triple(generator, HttpMethod.POST to GeneratorApi.SIMULATIONS_PATH, listOf("201", "400", "409")),
            Triple(generator, HttpMethod.GET to GeneratorApi.SIMULATION_PATH, listOf("200", "404")),
            Triple(generator, HttpMethod.POST to GeneratorApi.STOP_PATH, listOf("200", "202")),
            Triple(generator, HttpMethod.GET to GeneratorApi.RECONCILIATION_PATH, listOf("200", "404", "409", "502", "503")),
        )
        answers.forEach { (spec, call, codes) ->
            val (method, path) = call
            assertThat(spec.operation(method, path).get("responses").propertyNames()).`as`("$method $path").containsAll(codes)
        }
    }

    @Test
    fun `a field control reads as never null is never null in the spec`() {
        val models = mapOf(
            ledger to listOf(ReconciliationReport::class),
            gateway to listOf(RunSummary::class, ClosedPeriod::class),
            generator to listOf(
                SimulationStatus::class, ClientReconciliationReport::class, JournalTotals::class, StatementTotals::class,
                ReconciliationBreaks::class, TransferIds::class, UnknownResolved::class,
            ),
        )
        models.forEach { (spec, types) ->
            types.forEach { type ->
                val properties = spec.schemaOf(type).get("properties")
                type.primaryConstructor!!.parameters.filterNot { it.type.isMarkedNullable }.forEach { field ->
                    val property = properties.get(field.name)
                    val types = property.get("type")
                    val alternatives = listOfNotNull(property.get("oneOf"), property.get("anyOf")).flatMap { it.values() }
                    val nullable = (types != null && types.isArray && types.names().contains(NULL_TYPE)) ||
                        alternatives.any { it.get("type")?.asString() == NULL_TYPE }
                    assertThat(nullable).`as`("${type.simpleName}.${field.name}").isFalse()
                }
            }
        }
    }

    @Test
    fun `control sends every field the spec requires`() {
        listOf(ledger to OpenAccountsRequest::class, generator to StartSimulationRequest::class).forEach { (spec, type) ->
            val required = spec.schemaOf(type).get("required")?.names().orEmpty()
            assertThat(fieldsOf(type)).`as`(type.simpleName).containsAll(required)
        }
    }

    @Test
    fun `simulation states and ledger checks are the ones the specs list`() {
        val states = generator.schemaOf(SimulationStatus::class).get("properties").get("state").get("enum").names()
        assertThat(SimulationState.entries.map { it.name }).containsExactlyInAnyOrderElementsOf(states)

        val checks = ledger.schemaOf(ReconciliationReport::class).get("properties").get("failedChecks").get("items").get("enum").names()
        assertThat(ReconciliationCheck.entries.map { it.name }).containsExactlyInAnyOrderElementsOf(checks)
    }
}
