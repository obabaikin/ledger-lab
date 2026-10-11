package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.Statement
import com.ledgerlab.generator.bank.StatementLine
import com.ledgerlab.generator.bank.SubmitTransferRequest
import com.ledgerlab.generator.bank.TransferResponse
import com.ledgerlab.generator.bank.TransferStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/** The generator's model of the bank (package bank) against the bank's published spec, gateway/openapi.json (ADR 0011). */
class BankContractTest {

    private val spec: JsonNode = jacksonObjectMapper().readTree(File(System.getProperty("bank.spec")))
    private val schemas: JsonNode = spec.get("components").get("schemas")

    private fun schemaOf(type: KClass<*>): JsonNode = checkNotNull(schemas.get(type.simpleName)) { "no schema ${type.simpleName} in the spec" }

    private fun fieldsOf(type: KClass<*>): List<String> = type.primaryConstructor!!.parameters.map { it.name!! }

    private fun JsonNode.names(): List<String> = values().map { it.asString() }

    @Test
    fun `the path and query parameter the generator calls are in the spec`() {
        val submit = checkNotNull(spec.get("paths").get(GatewayApi.TRANSFERS_PATH)?.get("post")) { "no POST ${GatewayApi.TRANSFERS_PATH} in the spec" }

        assertThat(submit.get("parameters").values().map { it.get("name").asString() }).contains(GatewayApi.WAIT_PARAM)
    }

    @Test
    fun `the statement path, its paging parameters and the not-closed answer are in the spec`() {
        val statement = checkNotNull(spec.get("paths").get(GatewayApi.STATEMENT_PATH)?.get("get")) { "no GET ${GatewayApi.STATEMENT_PATH} in the spec" }

        assertThat(statement.get("parameters").values().map { it.get("name").asString() })
            .contains(GatewayApi.AFTER_PARAM, GatewayApi.LIMIT_PARAM)
        assertThat(statement.get("responses").propertyNames()).contains(HttpStatus.CONFLICT.value().toString())
    }

    @Test
    fun `every field the generator sends or reads is in the spec`() {
        listOf(SubmitTransferRequest::class, TransferResponse::class, Statement::class, StatementLine::class).forEach { type ->
            assertThat(schemaOf(type).get("properties").propertyNames()).`as`(type.simpleName).containsAll(fieldsOf(type))
        }
    }

    @Test
    fun `the generator sends every field the spec requires`() {
        val type = SubmitTransferRequest::class
        assertThat(fieldsOf(type)).containsAll(schemaOf(type).get("required").names())
    }

    @Test
    fun `the spec promises the statement's entry count on every page, since completeness rests on it`() {
        assertThat(schemaOf(Statement::class).get("required").names()).contains(Statement::entryCount.name)
    }

    @Test
    fun `statuses and reject reasons are the ones the spec lists, in answers and in the statement`() {
        listOf(TransferResponse::class, StatementLine::class).forEach { type ->
            val properties = schemaOf(type).get("properties")

            assertThat(TransferStatus.entries.map { it.name }).`as`(type.simpleName)
                .containsExactlyInAnyOrderElementsOf(properties.get("status").get("enum").names())
            assertThat(RejectReason.entries.map { it.name }).`as`(type.simpleName)
                .containsExactlyInAnyOrderElementsOf(properties.get("rejectReason").get("enum").names())
        }
    }
}
