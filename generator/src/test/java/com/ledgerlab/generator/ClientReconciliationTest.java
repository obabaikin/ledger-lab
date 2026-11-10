package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.StatementLine;
import com.ledgerlab.generator.bank.TransferStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientReconciliationTest {

    private final UUID runId = UUID.randomUUID();
    private long seq = 0L;

    private JournalEntryView entry(JournalOutcome outcome) {
        return entry(outcome, null, null);
    }

    private JournalEntryView entry(JournalOutcome outcome, TransferStatus status) {
        return entry(outcome, status, null);
    }

    private JournalEntryView entry(JournalOutcome outcome, TransferStatus status, RejectReason reason) {
        return entry(outcome, status, reason, 100);
    }

    private JournalEntryView entry(JournalOutcome outcome, TransferStatus status, RejectReason reason, long amountCents) {
        return new JournalEntryView(++seq, UUID.randomUUID(), 1, 2, amountCents, outcome, status, reason, null, List.of());
    }

    private static StatementLine line(JournalEntryView entry) {
        return line(entry, TransferStatus.POSTED, null);
    }

    private static StatementLine line(JournalEntryView entry, TransferStatus status, RejectReason reason) {
        return new StatementLine(entry.transferId(), entry.fromAccount(), entry.toAccount(), entry.amountCents(), status, reason);
    }

    private static StatementLine lineWith(JournalEntryView entry, int fromAccount, int toAccount, long amountCents) {
        return new StatementLine(entry.transferId(), fromAccount, toAccount, amountCents, TransferStatus.POSTED, null);
    }

    private static ReconciledJournal asJournal(List<JournalEntryView> entries) {
        return new ReconciledJournal() {
            @Override
            public int size() {
                return entries.size();
            }

            @Override
            public ReconciledEntry find(UUID transferId) {
                return entries.stream().filter(it -> it.transferId().equals(transferId)).findFirst().orElse(null);
            }

            @Override
            public void forEach(Consumer<ReconciledEntry> action) {
                entries.forEach(action);
            }
        };
    }

    private ClientReconciliationReport reconcile(List<JournalEntryView> journal, List<StatementLine> statement) {
        var reconciliation = new ClientReconciliation(runId, asJournal(journal), 0);
        statement.forEach(reconciliation::add);
        return reconciliation.report();
    }

    private static List<TransferIds> all(ReconciliationBreaks breaks) {
        return List.of(
            breaks.notInJournal(), breaks.acknowledgedNotInStatement(), breaks.failedButInStatement(), breaks.payloadMismatch(), breaks.statusMismatch());
    }

    @Test
    void an_empty_run_against_an_empty_statement_is_balanced() {
        var report = reconcile(List.of(), List.of());

        assertThat(report.balanced()).isTrue();
        assertThat(report.statementFinal()).isTrue();
        assertThat(report.journal()).isEqualTo(new JournalTotals(0, 0, 0, 0, 0, 0));
        assertThat(report.statement()).isEqualTo(new StatementTotals(0, 0, Map.of()));
    }

    @Test
    void a_line_added_again_is_refused_and_counted_once() {
        var posted = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, 300);
        var reconciliation = new ClientReconciliation(runId, asJournal(List.of(posted)), 0);

        var first = reconciliation.add(line(posted));
        var again = reconciliation.add(lineWith(posted, posted.fromAccount(), posted.toAccount(), 999));

        assertThat(first).isTrue();
        assertThat(again).isFalse();
        var report = reconciliation.report();
        assertThat(report.statement()).isEqualTo(new StatementTotals(1, 300, Map.of(TransferStatus.POSTED, 1L)));
        assertThat(report.breaks().payloadMismatch().count()).isZero();
    }

    @Test
    void every_acknowledged_transfer_found_with_the_same_payload_and_status_is_balanced() {
        var posted = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, 300);
        var rejected = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT);
        var accepted = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED);

        var report = reconcile(
            List.of(posted, rejected, accepted),
            List.of(line(posted), line(rejected, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT), line(accepted)));

        assertThat(report.balanced()).isTrue();
        assertThat(all(report.breaks())).allSatisfy(it -> assertThat(it.count()).isZero());
        assertThat(report.journal()).isEqualTo(new JournalTotals(3, 3, 0, 0, 500, 0));
        assertThat(report.statement()).isEqualTo(
            new StatementTotals(3, 500, Map.of(TransferStatus.POSTED, 2L, TransferStatus.REJECTED, 1L)));
    }

    @Test
    void a_transfer_the_bank_has_and_the_client_does_not_know_breaks_the_run() {
        var stranger = entry(JournalOutcome.ACKNOWLEDGED);

        var report = reconcile(List.of(), List.of(line(stranger)));

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().notInJournal()).isEqualTo(new TransferIds(1, List.of(stranger.transferId())));
    }

    @Test
    void a_transfer_acknowledged_to_the_client_and_missing_from_the_statement_breaks_the_run() {
        var lost = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED);

        var report = reconcile(List.of(lost), List.of());

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().acknowledgedNotInStatement()).isEqualTo(new TransferIds(1, List.of(lost.transferId())));
    }

    @Test
    void a_transfer_refused_to_the_client_and_accepted_by_the_bank_breaks_the_run() {
        var refused = entry(JournalOutcome.FAILED);
        var refusedForGood = entry(JournalOutcome.FAILED);

        var report = reconcile(List.of(refused, refusedForGood), List.of(line(refused)));

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().failedButInStatement()).isEqualTo(new TransferIds(1, List.of(refused.transferId())));
        assertThat(report.journal().failed()).isEqualTo(2);
    }

    @Test
    @DisplayName("a different amount, sending or receiving account breaks the run")
    void a_different_amount_sending_or_receiving_account_breaks_the_run() {
        var amount = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED);
        var sender = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED);
        var receiver = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED);

        var report = reconcile(
            List.of(amount, sender, receiver),
            List.of(
                lineWith(amount, amount.fromAccount(), amount.toAccount(), amount.amountCents() + 1),
                lineWith(sender, sender.fromAccount() + 1, sender.toAccount(), sender.amountCents()),
                lineWith(receiver, receiver.fromAccount(), receiver.toAccount() + 1, receiver.amountCents())));

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().payloadMismatch().transferIds())
            .containsExactlyInAnyOrder(amount.transferId(), sender.transferId(), receiver.transferId());
    }

    @Test
    void a_final_status_or_reject_reason_the_bank_does_not_confirm_breaks_the_run() {
        var postedToClient = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED);
        var otherReason = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS);

        var report = reconcile(
            List.of(postedToClient, otherReason),
            List.of(
                line(postedToClient, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT),
                line(otherReason, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT)));

        assertThat(report.balanced()).isFalse();
        assertThat(report.breaks().statusMismatch().transferIds()).containsExactlyInAnyOrder(postedToClient.transferId(), otherReason.transferId());
    }

    @Test
    @DisplayName("a reject reason one side could not read is not compared, the status still is")
    void a_reject_reason_one_side_could_not_read_is_not_compared_the_status_still_is() {
        var unreadToClient = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, null);
        var unreadInStatement = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT);
        var postedToClient = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED);

        var report = reconcile(
            List.of(unreadToClient, unreadInStatement, postedToClient),
            List.of(
                line(unreadToClient, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT),
                line(unreadInStatement, TransferStatus.REJECTED, null),
                line(postedToClient, TransferStatus.REJECTED, null)));

        assertThat(report.breaks().statusMismatch().transferIds()).containsExactly(postedToClient.transferId());
        assertThat(report.statementFinal()).isTrue();
    }

    @Test
    @DisplayName("a status the client knows only as accepted, or could not read, is not compared")
    void a_status_the_client_knows_only_as_accepted_or_could_not_read_is_not_compared() {
        var waiting = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED);
        var published = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.PUBLISHED);
        var unread = entry(JournalOutcome.ACKNOWLEDGED, null);

        var report = reconcile(
            List.of(waiting, published, unread),
            List.of(
                line(waiting, TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS),
                line(published),
                line(unread, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT)));

        assertThat(report.balanced()).isTrue();
    }

    @Test
    void unknown_transfers_are_settled_by_the_statement_without_breaking_the_run() {
        var arrived = entry(JournalOutcome.UNKNOWN);
        var neverArrived = entry(JournalOutcome.UNKNOWN);

        var report = reconcile(List.of(arrived, neverArrived), List.of(line(arrived)));

        assertThat(report.balanced()).isTrue();
        assertThat(report.unknownResolved()).isEqualTo(
            new UnknownResolved(new TransferIds(1, List.of(arrived.transferId())), new TransferIds(1, List.of(neverArrived.transferId()))));
        assertThat(report.journal().unknown()).isEqualTo(2);
    }

    @Test
    @DisplayName("a statement the ledger has not finished is reported as not final, with the same verdict")
    void a_statement_the_ledger_has_not_finished_is_reported_as_not_final_with_the_same_verdict() {
        var first = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED);
        var second = entry(JournalOutcome.UNKNOWN);
        var journal = List.of(first, second);

        var early = reconcile(journal, List.of(line(first, TransferStatus.ACCEPTED, null), line(second, TransferStatus.PUBLISHED, null)));
        var late = reconcile(journal, List.of(line(first), line(second)));

        assertThat(early.statementFinal()).isFalse();
        assertThat(late.statementFinal()).isTrue();
        assertThat(new ClientReconciliationReport(
            early.runId(), early.balanced(), true, early.journal(), late.statement(), early.breaks(), early.unknownResolved()))
            .isEqualTo(late);
    }

    @Test
    void a_transfer_still_pending_when_the_run_ended_is_settled_by_the_statement_like_an_unknown() {
        var found = entry(JournalOutcome.PENDING);
        var lost = entry(JournalOutcome.PENDING);

        var report = reconcile(List.of(found, lost), List.of(line(found, TransferStatus.POSTED, null)));

        assertThat(report.balanced()).isTrue();
        assertThat(report.journal().unknown()).isEqualTo(2);
        assertThat(report.unknownResolved().accepted().transferIds()).containsExactly(found.transferId());
        assertThat(report.unknownResolved().notAccepted().transferIds()).containsExactly(lost.transferId());
    }

    @Test
    void a_line_whose_status_the_client_cannot_read_is_not_compared_and_keeps_the_statement_from_being_final() {
        var posted = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED);
        var rejected = entry(JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT);

        var report = reconcile(List.of(posted, rejected), List.of(line(posted, null, null), line(rejected, null, null)));

        assertThat(report.breaks().statusMismatch().count()).isZero();
        assertThat(report.balanced()).isTrue();
        assertThat(report.statementFinal()).isFalse();
        assertThat(report.statement().rows()).isEqualTo(2);
        assertThat(report.statement().byStatus()).isEmpty();
    }

    @Test
    void a_category_lists_at_most_a_thousand_ids_and_counts_all_of_them() {
        var strangers = Stream.generate(() -> entry(JournalOutcome.ACKNOWLEDGED)).limit(ClientReconciliation.MAX_LISTED_IDS + 1).toList();

        var report = reconcile(List.of(), strangers.stream().map(ClientReconciliationTest::line).toList());

        assertThat(report.breaks().notInJournal().count()).isEqualTo(ClientReconciliation.MAX_LISTED_IDS + 1L);
        assertThat(report.breaks().notInJournal().transferIds()).hasSize(ClientReconciliation.MAX_LISTED_IDS);
    }
}
