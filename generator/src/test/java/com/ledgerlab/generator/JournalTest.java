package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JournalTest {

    private final long start = System.nanoTime();
    private final Journal journal = new Journal(start);

    private JournalEntry add() {
        return journal.add(TestData.transfer());
    }

    private void respond(JournalEntry entry, int httpStatus) {
        respond(entry, httpStatus, SendKind.FIRST);
    }

    private void respond(JournalEntry entry, int httpStatus, SendKind kind) {
        entry.record(kind, start, start + 1_000_000, SendResult.RESPONSE, httpStatus);
    }

    private List<UUID> ids(JournalFilter filter) {
        return journal.page(filter, 0, 100, true).items().stream().map(JournalEntryView::transferId).toList();
    }

    @Test
    void pages_follow_the_order_of_first_send_and_end_only_when_the_journal_is_complete() {
        for (var i = 0; i < 5; i++) {
            respond(add(), 202);
        }

        var first = journal.page(TestData.anyEntry(), 0, 2, true);
        var second = journal.page(TestData.anyEntry(), first.next(), 2, true);
        var last = journal.page(TestData.anyEntry(), second.next(), 2, true);

        assertThat(first.items()).extracting(JournalEntryView::seq).containsExactly(1L, 2L);
        assertThat(second.items()).extracting(JournalEntryView::seq).containsExactly(3L, 4L);
        assertThat(last.items()).extracting(JournalEntryView::seq).containsExactly(5L);
        assertThat(last.next()).isNull();

        var live = journal.page(TestData.anyEntry(), 0, 100, false);
        assertThat(live.items()).hasSize(5);
        assertThat(live.next()).isEqualTo(5);
        var nextEntry = add();
        assertThat(journal.page(TestData.anyEntry(), live.next(), 100, false).items()).extracting(JournalEntryView::seq)
            .containsExactly(nextEntry.seq());
    }

    @Test
    void filters_combine_and_skip_entries_without_losing_the_cursor() {
        var pending = add();
        var retried = add();
        respond(retried, 503);
        respond(retried, 202, SendKind.RETRY);
        retried.settle(JournalOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED, null);
        var rejected = add();
        respond(rejected, 200);
        rejected.settle(JournalOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT);
        var failed = add();
        respond(failed, 422);
        failed.settle(JournalOutcome.FAILED);

        assertThat(ids(new JournalFilter(JournalOutcome.PENDING, null, null))).containsExactly(pending.transferId());
        assertThat(ids(new JournalFilter(null, true, null))).containsExactly(retried.transferId());
        assertThat(ids(new JournalFilter(null, null, RejectReason.BLOCKED_ACCOUNT))).containsExactly(rejected.transferId());
        assertThat(ids(new JournalFilter(JournalOutcome.ACKNOWLEDGED, false, null))).containsExactly(rejected.transferId());
        assertThat(ids(new JournalFilter(JournalOutcome.FAILED, null, null))).containsExactly(failed.transferId());

        var page = journal.page(new JournalFilter(null, true, null), 0, 1, true);
        assertThat(page.next()).isEqualTo(retried.seq());
        assertThat(journal.page(new JournalFilter(null, true, null), page.next(), 1, true))
            .isEqualTo(new JournalPage(List.of(), null));
    }

    @Test
    @DisplayName("the first send is the entry itself, later ones are numbered from 2")
    void the_first_send_is_the_entry_itself_later_ones_are_numbered_from_2() {
        var entry = add();

        entry.record(SendKind.FIRST, start + 2_000_000, start + 5_000_000, SendResult.NO_RESPONSE, null);
        entry.record(SendKind.RETRY, start + 1_005_000_000, start + 1_010_000_000, SendResult.RESPONSE, 202);

        var view = journal.get(entry.transferId());
        assertThat(view.first()).isEqualTo(new Send(2, SendResult.NO_RESPONSE, null, 3));
        assertThat(view.resends()).containsExactly(new Resend(ResendKind.RETRY, 2, 1_005, SendResult.RESPONSE, 202, 5));
        assertThat(journal.get(UUID.randomUUID())).isNull();
    }

    @Test
    void a_duplicate_that_ends_before_the_first_send_does_not_take_its_place() {
        var entry = add();

        entry.record(SendKind.DUPLICATE, start + 1_000_000, start + 4_000_000, SendResult.RESPONSE, 202);
        entry.record(SendKind.FIRST, start, start + 900_000_000, SendResult.RESPONSE, 503);
        entry.record(SendKind.RETRY, start + 950_000_000, start + 960_000_000, SendResult.RESPONSE, 200);

        var view = journal.get(entry.transferId());
        assertThat(view.first()).isEqualTo(new Send(0, SendResult.RESPONSE, 503, 900));
        assertThat(view.resends()).containsExactly(
            new Resend(ResendKind.DUPLICATE, 2, 1, SendResult.RESPONSE, 202, 3),
            new Resend(ResendKind.RETRY, 3, 950, SendResult.RESPONSE, 200, 10));
        assertThat(journal.page(new JournalFilter(null, true, null), 0, 10, true).items()).hasSize(1);
    }
}
