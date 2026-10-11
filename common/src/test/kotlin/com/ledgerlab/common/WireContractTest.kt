package com.ledgerlab.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID

/**
 * The Kafka messages between ledger and gateway as they look on the wire. Names are written out
 * as literals on purpose: renaming a field or an enum value must fail here, not change the wire.
 */
class WireContractTest {

    private val mapper = jacksonObjectMapper()

    private fun fieldsOf(value: Any): Set<String> = mapper.valueToTree<JsonNode>(value).propertyNames().toSet()

    @Test
    fun `a transfer command carries these fields`() {
        val command = TransferCommand(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100, Instant.now())

        assertThat(fieldsOf(command)).containsExactlyInAnyOrder(
            "runId", "transferId", "fromAccount", "toAccount", "amountCents", "createdAt",
        )
    }

    @Test
    fun `a result event carries these fields`() {
        val event = TransferResultEvent(UUID.randomUUID(), UUID.randomUUID(), TransferOutcome.REJECTED, RejectReason.SAME_ACCOUNT, Instant.now())

        assertThat(fieldsOf(event)).containsExactlyInAnyOrder("runId", "transferId", "outcome", "rejectReason", "processedAt")
    }

    @Test
    fun `outcomes and reject reasons travel by these names`() {
        assertThat(TransferOutcome.entries.map { it.name }).containsExactly("POSTED", "REJECTED")
        assertThat(RejectReason.entries.map { it.name }).containsExactly(
            "INVALID_AMOUNT", "SAME_ACCOUNT", "UNKNOWN_ACCOUNT", "BLOCKED_ACCOUNT", "INSUFFICIENT_FUNDS", "DAILY_LIMIT_EXCEEDED",
        )
    }

    @Test
    fun `a result event written by hand reads back`() {
        val runId = UUID.randomUUID()
        val transferId = UUID.randomUUID()
        val json = """{"runId":"$runId","transferId":"$transferId","outcome":"REJECTED","rejectReason":"BLOCKED_ACCOUNT","processedAt":"2026-10-11T10:00:00Z"}"""

        val event = mapper.readValue<TransferResultEvent>(json)

        assertThat(event).isEqualTo(
            TransferResultEvent(runId, transferId, TransferOutcome.REJECTED, RejectReason.BLOCKED_ACCOUNT, Instant.parse("2026-10-11T10:00:00Z")),
        )
    }
}
