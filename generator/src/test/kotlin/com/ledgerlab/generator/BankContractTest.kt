package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.SubmitTransferRequest
import com.ledgerlab.generator.bank.TransferResponse
import com.ledgerlab.generator.bank.TransferStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/** The generator's model of the bank (package bank) against the bank's published spec, gateway/openapi.json (ADR 0011). */
class BankContractTest {

    private val schemas: JsonNode = jacksonObjectMapper().readTree(File(System.getProperty("bank.spec"))).get("components").get("schemas")

    private fun schemaOf(type: KClass<*>): JsonNode = checkNotNull(schemas.get(type.simpleName)) { "no schema ${type.simpleName} in the spec" }

    private fun fieldsOf(type: KClass<*>): List<String> = type.primaryConstructor!!.parameters.map { it.name!! }

    private fun JsonNode.names(): List<String> = values().map { it.asString() }

    @Test
    fun `every field the generator sends or reads is in the spec`() {
        listOf(SubmitTransferRequest::class, TransferResponse::class).forEach { type ->
            assertThat(schemaOf(type).get("properties").propertyNames()).`as`(type.simpleName).containsAll(fieldsOf(type))
        }
    }

    @Test
    fun `the generator sends every field the spec requires`() {
        val type = SubmitTransferRequest::class
        assertThat(fieldsOf(type)).containsAll(schemaOf(type).get("required").names())
    }

    @Test
    fun `statuses and reject reasons are the ones the spec lists`() {
        val properties = schemaOf(TransferResponse::class).get("properties")

        assertThat(TransferStatus.entries.map { it.name }).containsExactlyInAnyOrderElementsOf(properties.get("status").get("enum").names())
        assertThat(RejectReason.entries.map { it.name }).containsExactlyInAnyOrderElementsOf(properties.get("rejectReason").get("enum").names())
    }
}
