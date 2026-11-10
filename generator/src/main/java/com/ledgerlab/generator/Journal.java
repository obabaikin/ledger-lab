package com.ledgerlab.generator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Every transfer of one run, in the order of first send; seq starts at 1 and is the paging cursor. */
public final class Journal implements ReconciledJournal {

    private final long runStartNanos;
    private final ArrayList<JournalEntry> entries = new ArrayList<>();
    private final HashMap<UUID, JournalEntry> byId = new HashMap<>();

    public Journal(long runStartNanos) {
        this.runStartNanos = runStartNanos;
    }

    public synchronized JournalEntry add(PlannedTransfer transfer) {
        var entry = new JournalEntry(entries.size() + 1L, transfer, runStartNanos);
        entries.add(entry);
        byId.put(transfer.transferId(), entry);
        return entry;
    }

    public synchronized @Nullable JournalEntryView get(UUID transferId) {
        var entry = byId.get(transferId);
        return entry == null ? null : entry.view();
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public synchronized @Nullable ReconciledEntry find(UUID transferId) {
        return byId.get(transferId);
    }

    @Override
    public synchronized void forEach(Consumer<ReconciledEntry> action) {
        entries.forEach(action);
    }

    /** {@code complete}: no entry will be added any more, so reaching the end means there is no next page. */
    public synchronized JournalPage page(JournalFilter filter, long after, int limit, boolean complete) {
        var items = new ArrayList<JournalEntryView>();
        var index = (int) Math.clamp(after, 0L, (long) entries.size());
        while (index < entries.size() && items.size() < limit) {
            var view = entries.get(index++).view();
            if (filter.matches(view)) {
                items.add(view);
            }
        }
        var next = index < entries.size() || !complete ? Long.valueOf(index) : null;
        return new JournalPage(items, next);
    }
}
