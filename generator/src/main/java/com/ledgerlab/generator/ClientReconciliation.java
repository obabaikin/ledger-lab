package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.StatementLine;
import com.ledgerlab.generator.bank.TransferStatus;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Statement lines are fed page by page and the journal is read in place, so neither is copied: a line
 * found in the journal is marked by the entry's seq, only the ids of lines not in it are held.
 */
public final class ClientReconciliation {

    public static final int MAX_LISTED_IDS = 1_000;

    private final UUID runId;
    private final ReconciledJournal journal;
    private final long refused;
    private final BitSet inStatement = new BitSet();
    private final HashSet<UUID> notInJournalSeen = new HashSet<>();
    private final IdCollector notInJournal = new IdCollector();
    private final IdCollector failedButInStatement = new IdCollector();
    private final IdCollector payloadMismatch = new IdCollector();
    private final IdCollector statusMismatch = new IdCollector();
    private final IdCollector unknownAccepted = new IdCollector();
    private long rows = 0L;
    private long amountCents = 0L;
    private final EnumMap<TransferStatus, Long> byStatus = new EnumMap<>(TransferStatus.class);
    private boolean unreadableStatus = false;

    private static final class IdCollector {
        private long count = 0L;
        private final ArrayList<UUID> ids = new ArrayList<>();

        void add(UUID id) {
            count++;
            if (ids.size() < MAX_LISTED_IDS) {
                ids.add(id);
            }
        }

        TransferIds result() {
            return new TransferIds(count, List.copyOf(ids));
        }
    }

    private static final class JournalCounts {
        private long acknowledged = 0L;
        private long acknowledgedAmountCents = 0L;
        private long failed = 0L;
        private long unknown = 0L;
    }

    public ClientReconciliation(UUID runId, ReconciledJournal journal, long refused) {
        this.runId = runId;
        this.journal = journal;
        this.refused = refused;
    }

    private static boolean knownReasonsDiffer(@Nullable RejectReason client, @Nullable RejectReason bank) {
        return client != null && bank != null && client != bank;
    }

    public boolean add(StatementLine line) {
        var entry = journal.find(line.transferId());
        var firstTime = entry == null ? notInJournalSeen.add(line.transferId()) : !inStatement.get((int) entry.seq());
        if (!firstTime) {
            return false;
        }
        rows++;
        amountCents += line.amountCents();
        var lineStatus = line.status();
        if (lineStatus == null) {
            unreadableStatus = true;
        } else {
            byStatus.merge(lineStatus, 1L, Long::sum);
        }
        if (entry == null) {
            notInJournal.add(line.transferId());
            return true;
        }
        inStatement.set((int) entry.seq());
        if (entry.amountCents() != line.amountCents() || entry.fromAccount() != line.fromAccount() || entry.toAccount() != line.toAccount()) {
            payloadMismatch.add(line.transferId());
        }
        switch (entry.outcome()) {
            case FAILED -> failedButInStatement.add(line.transferId());
            case UNKNOWN, PENDING -> unknownAccepted.add(line.transferId());
            case ACKNOWLEDGED -> {
                var gatewayStatus = entry.gatewayStatus();
                if (gatewayStatus != null && gatewayStatus.isFinal() && lineStatus != null
                    && (gatewayStatus != lineStatus || knownReasonsDiffer(entry.rejectReason(), line.rejectReason()))) {
                    statusMismatch.add(line.transferId());
                }
            }
        }
        return true;
    }

    public ClientReconciliationReport report() {
        var acknowledgedNotInStatement = new IdCollector();
        var unknownNotAccepted = new IdCollector();
        var totals = new JournalCounts();
        journal.forEach(entry -> {
            var found = inStatement.get((int) entry.seq());
            switch (entry.outcome()) {
                case ACKNOWLEDGED -> {
                    totals.acknowledged++;
                    totals.acknowledgedAmountCents += entry.amountCents();
                    if (!found) {
                        acknowledgedNotInStatement.add(entry.transferId());
                    }
                }
                case UNKNOWN, PENDING -> {
                    totals.unknown++;
                    if (!found) {
                        unknownNotAccepted.add(entry.transferId());
                    }
                }
                case FAILED -> totals.failed++;
            }
        });
        var breaks = new ReconciliationBreaks(
            notInJournal.result(), acknowledgedNotInStatement.result(), failedButInStatement.result(),
            payloadMismatch.result(), statusMismatch.result());
        return new ClientReconciliationReport(
            runId,
            Stream.of(
                breaks.notInJournal(), breaks.acknowledgedNotInStatement(), breaks.failedButInStatement(),
                breaks.payloadMismatch(), breaks.statusMismatch())
                .allMatch(it -> it.count() == 0L),
            !unreadableStatus && byStatus.keySet().stream().allMatch(TransferStatus::isFinal),
            new JournalTotals(journal.size(), totals.acknowledged, totals.failed, totals.unknown, totals.acknowledgedAmountCents, refused),
            new StatementTotals(rows, amountCents, new LinkedHashMap<>(byStatus)),
            breaks,
            new UnknownResolved(unknownAccepted.result(), unknownNotAccepted.result()));
    }
}
