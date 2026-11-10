package com.ledgerlab.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Kafka messages between ledger and gateway as they look on the wire. Names are written out
 * as literals on purpose: renaming a field or an enum value must fail here, not change the wire.
 */
class WireContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Set<String> fieldsOf(Object value) {
        return Set.copyOf(mapper.<JsonNode>valueToTree(value).propertyNames());
    }

    @Test
    void a_transfer_command_carries_these_fields() {
        var command = new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100, Instant.now());

        assertThat(fieldsOf(command)).containsExactlyInAnyOrder(
            "runId", "transferId", "fromAccount", "toAccount", "amountCents", "createdAt");
    }

    @Test
    void a_result_event_carries_these_fields() {
        var event = new TransferResultEvent(
            UUID.randomUUID(), UUID.randomUUID(), TransferOutcome.REJECTED, RejectReason.SAME_ACCOUNT, Instant.now());

        assertThat(fieldsOf(event)).containsExactlyInAnyOrder("runId", "transferId", "outcome", "rejectReason", "processedAt");
    }

    @Test
    void outcomes_and_reject_reasons_travel_by_these_names() {
        assertThat(Arrays.stream(TransferOutcome.values()).map(Enum::name)).containsExactly("POSTED", "REJECTED");
        assertThat(Arrays.stream(RejectReason.values()).map(Enum::name)).containsExactly(
            "INVALID_AMOUNT", "SAME_ACCOUNT", "UNKNOWN_ACCOUNT", "BLOCKED_ACCOUNT", "INSUFFICIENT_FUNDS", "DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void a_result_event_written_by_hand_reads_back() {
        var runId = UUID.randomUUID();
        var transferId = UUID.randomUUID();
        var json = """
            {"runId":"%s","transferId":"%s","outcome":"REJECTED","rejectReason":"BLOCKED_ACCOUNT","processedAt":"2026-10-11T10:00:00Z"}"""
            .formatted(runId, transferId);

        var event = mapper.readValue(json, TransferResultEvent.class);

        assertThat(event).isEqualTo(new TransferResultEvent(
            runId, transferId, TransferOutcome.REJECTED, RejectReason.BLOCKED_ACCOUNT, Instant.parse("2026-10-11T10:00:00Z")));
    }

    @Test
    void a_message_without_a_required_field_is_unreadable_and_not_read_as_an_empty_value() {
        var command = """
            {"runId":"%s","fromAccount":1,"toAccount":2,"amountCents":100,"createdAt":"2026-10-11T10:00:00Z"}"""
            .formatted(UUID.randomUUID());
        var event = """
            {"runId":"%s","transferId":"%s","outcome":null,"processedAt":"2026-10-11T10:00:00Z"}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> mapper.readValue(command, TransferCommand.class)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue(event, TransferResultEvent.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void a_result_event_without_a_reject_reason_reads_with_none() {
        var json = """
            {"runId":"%s","transferId":"%s","outcome":"POSTED","processedAt":"2026-10-11T10:00:00Z"}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThat(mapper.readValue(json, TransferResultEvent.class).rejectReason()).isNull();
    }
}
