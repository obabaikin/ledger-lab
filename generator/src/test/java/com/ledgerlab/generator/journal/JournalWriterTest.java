package com.ledgerlab.generator.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.generator.FakeGateway;
import com.ledgerlab.generator.IntegrationTest;
import com.ledgerlab.generator.PlannedTransfer;
import com.ledgerlab.generator.TestData;
import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import com.ledgerlab.generator.gateway.GatewayClient;
import com.ledgerlab.generator.load.BaseSchedule;
import com.ledgerlab.generator.load.Load;
import com.ledgerlab.generator.load.ResponseMinutes;
import com.ledgerlab.generator.load.SendPermission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

class JournalWriterTest extends IntegrationTest {

    @Autowired JournalWriter writer;

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private long intentRows(UUID transferId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM intents WHERE transfer_id = ?", Long.class, transferId);
    }

    @Test
    void an_intent_is_stored_when_the_client_gets_the_word_to_send() throws Exception {
        var transfer = TestData.transfer();
        var sentAt = now();

        writer.intent(sentAt, transfer);

        var stored = jdbcTemplate.queryForMap("SELECT * FROM intents WHERE transfer_id = ?", transfer.transferId());
        assertThat(((Timestamp) stored.get("sent_at")).toInstant()).isEqualTo(sentAt);
        assertThat(stored).containsEntry("from_account", 1).containsEntry("to_account", 2).containsEntry("amount_cents", 100L);
        assertThat(outcomeRows(transfer.transferId())).isZero();
    }

    @Test
    void intents_of_many_clients_at_once_are_all_stored() throws Exception {
        var transfers = new ArrayList<PlannedTransfer>();
        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var written = new ArrayList<Future<?>>();
            for (var i = 0; i < 1_200; i++) {
                var transfer = TestData.transfer();
                transfers.add(transfer);
                written.add(clients.submit(() -> {
                    writer.intent(now(), transfer);
                    return null;
                }));
            }
            for (var one : written) {
                one.get();
            }
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intents", Long.class)).isEqualTo(1_200);
        assertThat(intentRows(transfers.getLast().transferId())).isEqualTo(1);
    }

    @Test
    void an_outcome_is_stored_with_what_the_bank_answered() throws Exception {
        var transfer = TestData.transfer();
        var sentAt = now();
        var acceptedAt = sentAt.plusMillis(3);
        writer.intent(sentAt, transfer);

        writer.outcome(sentAt, transfer, JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT, acceptedAt);

        await().atMost(Duration.ofSeconds(5)).until(() -> outcomeRows(transfer.transferId()) == 1);
        var stored = jdbcTemplate.queryForMap("SELECT * FROM outcomes WHERE transfer_id = ?", transfer.transferId());
        assertThat(stored).containsEntry("outcome", "ACKNOWLEDGED").containsEntry("bank_status", "REJECTED").containsEntry("reject_reason", "BLOCKED_ACCOUNT");
        assertThat(((Timestamp) stored.get("sent_at")).toInstant()).isEqualTo(sentAt);
        assertThat(((Timestamp) stored.get("accepted_at")).toInstant()).isEqualTo(acceptedAt);
    }

    @Test
    @DisplayName("an outcome written again does not make a second row, and the first one stands")
    void an_outcome_written_again_does_not_make_a_second_row_and_the_first_one_stands() throws Exception {
        var transfer = TestData.transfer();
        var sentAt = now();
        writer.intent(sentAt, transfer);

        writer.outcome(sentAt, transfer, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, sentAt);
        await().atMost(Duration.ofSeconds(5)).until(() -> outcomeRows(transfer.transferId()) == 1);
        writer.outcome(sentAt, transfer, JournalOutcome.FAILED, null, null, null);
        writer.writeNow(List.of(new JournalWriter.Outcome(sentAt, transfer.transferId(), JournalOutcome.UNKNOWN_NOT_ACCEPTED, null, null, null)));
        // The writer takes rows in order: once this intent is stored, the second outcome before it has been tried.
        writer.intent(now(), TestData.transfer());

        assertThat(outcomeRows(transfer.transferId())).isEqualTo(1);
        assertThat(storedOutcome(transfer.transferId())).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    @DisplayName("a batch the journal cannot write fails its intents, and the writer goes on with the next ones")
    void a_batch_the_journal_cannot_write_fails_its_intents_and_the_writer_goes_on_with_the_next_ones() throws Exception {
        var transfer = TestData.transfer();
        var sentAt = now();
        writer.intent(sentAt, transfer);

        // The same intent again breaks the key: its batch is rolled back.
        assertThatThrownBy(() -> writer.intent(sentAt, transfer)).isInstanceOf(JournalUnavailableException.class);
        var next = TestData.transfer();
        writer.intent(now(), next);

        assertThat(intentRows(transfer.transferId())).isEqualTo(1);
        assertThat(intentRows(next.transferId())).isEqualTo(1);
    }

    @Test
    void with_the_real_journal_the_intent_of_a_transfer_is_committed_before_its_request_reaches_the_bank() throws Exception {
        var arrivedWithoutIntent = new ConcurrentLinkedQueue<UUID>();
        gateway.reset(FakeGateway.with().onArrival(request -> {
            if (intentRows(request.transferId()) != 1) {
                arrivedWithoutIntent.add(request.transferId());
            }
        }));
        var settings = TestData.settings(gateway.url()).tps(200).build();
        var registry = new SimpleMeterRegistry();
        var clock = Clock.systemUTC();
        var load = new Load(settings, new GatewayClient(settings, new ObjectMapper(), registry), writer, new ResponseMinutes(clock, settings, new BaseSchedule(settings)), new BaseSchedule(settings), SendPermission.ALWAYS, clock, registry);

        load.begin();
        Thread.sleep(1_000);
        load.stop();

        var status = load.status();
        assertThat(gateway.requests.get()).isGreaterThan(100);
        assertThat(arrivedWithoutIntent).isEmpty();
        assertThat(status.acknowledged()).isEqualTo(status.requested());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM intents", Long.class)).isEqualTo(status.requested());
        await().atMost(Duration.ofSeconds(5))
            .until(() -> jdbcTemplate.queryForObject("SELECT count(*) FROM outcomes WHERE outcome = 'ACKNOWLEDGED'", Long.class) == status.requested());
    }
}
