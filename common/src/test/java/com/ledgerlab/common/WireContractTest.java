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

    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-11-10T10:00:01Z");

    private final ObjectMapper mapper = new ObjectMapper();

    private Set<String> fieldsOf(Object value) {
        return Set.copyOf(mapper.<JsonNode>valueToTree(value).propertyNames());
    }

    @Test
    void a_transfer_command_carries_these_fields() {
        var command = new TransferCommand(UUID.randomUUID(), 1, 2, 100, ACCEPTED_AT);

        assertThat(fieldsOf(command)).containsExactlyInAnyOrder("transferId", "fromAccount", "toAccount", "amountCents", "acceptedAt");
    }

    @Test
    void a_result_event_carries_these_fields() {
        var event = new TransferResultEvent(UUID.randomUUID(), TransferOutcome.REJECTED, RejectReason.SAME_ACCOUNT, PROCESSED_AT, ACCEPTED_AT);

        assertThat(fieldsOf(event)).containsExactlyInAnyOrder("transferId", "outcome", "rejectReason", "processedAt", "acceptedAt");
    }

    @Test
    void outcomes_and_reject_reasons_travel_by_these_names() {
        assertThat(Arrays.stream(TransferOutcome.values()).map(Enum::name)).containsExactly("POSTED", "REJECTED");
        assertThat(Arrays.stream(RejectReason.values()).map(Enum::name)).containsExactly(
            "INVALID_AMOUNT", "SAME_ACCOUNT", "UNKNOWN_ACCOUNT", "BLOCKED_ACCOUNT", "INSUFFICIENT_FUNDS");
    }

    @Test
    void transfers_of_one_sender_share_a_partition_key() {
        var first = new TransferCommand(UUID.randomUUID(), 7, 2, 100, ACCEPTED_AT);
        var second = new TransferCommand(UUID.randomUUID(), 7, 3, 500, PROCESSED_AT);
        var other = new TransferCommand(UUID.randomUUID(), 8, 2, 100, ACCEPTED_AT);

        assertThat(first.partitionKey()).isEqualTo(second.partitionKey()).isNotEqualTo(other.partitionKey());
    }

    @Test
    void a_result_event_written_by_hand_reads_back() {
        var transferId = UUID.randomUUID();
        var json = """
            {"transferId":"%s","outcome":"REJECTED","rejectReason":"BLOCKED_ACCOUNT","processedAt":"2026-11-10T10:00:01Z","acceptedAt":"2026-11-10T10:00:00Z"}"""
            .formatted(transferId);

        var event = mapper.readValue(json, TransferResultEvent.class);

        assertThat(event).isEqualTo(new TransferResultEvent(transferId, TransferOutcome.REJECTED, RejectReason.BLOCKED_ACCOUNT, PROCESSED_AT, ACCEPTED_AT));
    }

    @Test
    void a_message_without_a_required_field_is_unreadable_and_not_read_as_an_empty_value() {
        var withoutId = """
            {"fromAccount":1,"toAccount":2,"amountCents":100,"acceptedAt":"2026-11-10T10:00:00Z"}""";
        var withoutAmount = """
            {"transferId":"%s","fromAccount":1,"toAccount":2,"acceptedAt":"2026-11-10T10:00:00Z"}""".formatted(UUID.randomUUID());
        var withoutAcceptedAt = """
            {"transferId":"%s","fromAccount":1,"toAccount":2,"amountCents":100}""".formatted(UUID.randomUUID());
        var eventWithoutOutcome = """
            {"transferId":"%s","outcome":null,"processedAt":"2026-11-10T10:00:01Z","acceptedAt":"2026-11-10T10:00:00Z"}""".formatted(UUID.randomUUID());
        var eventWithoutAcceptedAt = """
            {"transferId":"%s","outcome":"POSTED","processedAt":"2026-11-10T10:00:01Z"}""".formatted(UUID.randomUUID());

        assertThatThrownBy(() -> mapper.readValue(withoutId, TransferCommand.class)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue(withoutAmount, TransferCommand.class)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue(withoutAcceptedAt, TransferCommand.class)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue(eventWithoutOutcome, TransferResultEvent.class)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue(eventWithoutAcceptedAt, TransferResultEvent.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void a_result_event_without_a_reject_reason_reads_with_none() {
        var json = """
            {"transferId":"%s","outcome":"POSTED","processedAt":"2026-11-10T10:00:01Z","acceptedAt":"2026-11-10T10:00:00Z"}""".formatted(UUID.randomUUID());

        assertThat(mapper.readValue(json, TransferResultEvent.class).rejectReason()).isNull();
    }
}
