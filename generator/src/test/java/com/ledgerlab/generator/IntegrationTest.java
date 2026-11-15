package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import com.ledgerlab.generator.journal.JournalOutcome;
import com.ledgerlab.generator.journal.Period;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * One generator with its journal for all tests of a context, and one stand-in bank: each test starts with an empty
 * journal, zero totals and a bank that has seen nothing, and puts the transfers of a period it reconciles into the last ended one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(AwayFromTheHoursEnd.class)
@AutoConfigureTestRestTemplate
public abstract class IntegrationTest {

    protected static final FakeGateway gateway = new FakeGateway();

    @DynamicPropertySource
    static void theBank(DynamicPropertyRegistry registry) {
        registry.add("generator.gateway-url", gateway::url);
    }

    @Autowired protected TestRestTemplate http;
    @Autowired protected GeneratorProperties props;
    @Autowired protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyJournalAndBank() {
        jdbcTemplate.update("DELETE FROM outcomes");
        jdbcTemplate.update("DELETE FROM intents");
        jdbcTemplate.update("DELETE FROM client_totals_delta");
        jdbcTemplate.update("UPDATE client_totals SET sent = 0, acknowledged = 0, failed = 0, unknown_accepted = 0, unknown_not_accepted = 0");
        gateway.reset(FakeGateway.with().periodLength(props.periods().length()));
    }

    protected Period lastEndedPeriod() {
        return Period.containing(Instant.now(), props.periods().length()).previous();
    }

    /** An intent as the journal holds it for a transfer of 100 from account 1 to account 2, sent then. */
    protected UUID intent(Instant sentAt) {
        return intent(sentAt, 1, 2, 100);
    }

    protected UUID intent(Instant sentAt, int from, int to, long amountCents) {
        var transferId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO intents (sent_at, transfer_id, from_account, to_account, amount_cents) VALUES (?, ?, ?, ?, ?)",
            Timestamp.from(sentAt), transferId, from, to, amountCents);
        return transferId;
    }

    protected void outcome(
        Instant sentAt, UUID transferId, JournalOutcome outcome, @Nullable TransferStatus status, @Nullable RejectReason reason, @Nullable Instant acceptedAt) {
        jdbcTemplate.update(
            "INSERT INTO outcomes (sent_at, transfer_id, outcome, bank_status, reject_reason, accepted_at) VALUES (?, ?, ?, ?, ?, ?)",
            Timestamp.from(sentAt), transferId, outcome.name(),
            status == null ? null : status.name(), reason == null ? null : reason.name(), acceptedAt == null ? null : Timestamp.from(acceptedAt));
    }

    /** A transfer both sides agree on: sent and accepted then, posted, told so to the client. */
    protected UUID agreed(Instant at) {
        var transferId = intent(at);
        outcome(at, transferId, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, at);
        gateway.accept(transferId, 1, 2, 100, TransferStatus.POSTED, null, at);
        return transferId;
    }

    protected long outcomeRows(UUID transferId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outcomes WHERE transfer_id = ?", Long.class, transferId);
    }

    protected @Nullable String storedOutcome(UUID transferId) {
        return jdbcTemplate.query("SELECT outcome FROM outcomes WHERE transfer_id = ?", rs -> rs.next() ? rs.getString(1) : null, transferId);
    }
}
