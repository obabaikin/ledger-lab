package com.ledgerlab.gateway.service;

import com.ledgerlab.common.Observability;
import com.ledgerlab.gateway.domain.RunSummary;
import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.persistence.RunPeriodRepository;
import com.ledgerlab.gateway.persistence.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final TransferRepository transfers;
    private final RunPeriodRepository periods;
    private final Counter accepted;
    private final Counter repeated;
    private final Timer latency;

    public TransferService(TransferRepository transfers, RunPeriodRepository periods, MeterRegistry registry) {
        this.transfers = transfers;
        this.periods = periods;
        this.accepted = Counter.builder("gateway.transfers").tag("outcome", "accepted").register(registry);
        this.repeated = Counter.builder("gateway.transfers").tag("outcome", "repeated").register(registry);
        this.latency = Timer.builder("gateway.transfer.latency")
            .description("accepted at the edge to result known")
            .publishPercentiles(Observability.latencyPercentiles())
            .register(registry);
    }

    /**
     * One transaction and one insert for many requests; each gets, in the same order, the outcome it would have had
     * alone: its row, or {@link PeriodClosedException} / {@link IdempotencyConflictException}. The responses go out after the
     * commit, before Kafka is involved.
     */
    @Transactional
    public List<AcceptOutcome> acceptAll(List<TransferRequest> requests) {
        // One order for every batch: a close waiting between two shared locks cannot close a cycle.
        requests.stream().map(TransferRequest::runId).distinct().sorted(Comparator.comparing(UUID::toString)).forEach(periods::lockShared);
        // A separate statement after the locks: its snapshot must see a close that committed while we waited.
        var runIds = new LinkedHashSet<UUID>();
        requests.forEach(it -> runIds.add(it.runId()));
        var closed = periods.closedAmong(runIds);
        var inserting = new LinkedHashMap<UUID, TransferRequest>();
        for (var request : requests) {
            if (!closed.contains(request.runId())) {
                inserting.putIfAbsent(request.transferId(), request);
            }
        }
        var inserted = byTransferId(transfers.insertAllIfAbsent(List.copyOf(inserting.values())));
        accepted.increment(inserted.size());
        var notInserted = new LinkedHashSet<UUID>();
        for (var request : requests) {
            if (!inserted.containsKey(request.transferId())) {
                notInserted.add(request.transferId());
            }
        }
        var existing = byTransferId(transfers.findAll(notInserted));
        var answeredAsInserted = new HashSet<UUID>();
        var outcomes = new ArrayList<AcceptOutcome>(requests.size());
        for (var request : requests) {
            try {
                var row = inserted.get(request.transferId());
                outcomes.add(new AcceptOutcome.Accepted(
                    outcomeOf(request, row, request.equals(inserting.get(request.transferId())), existing, closed, answeredAsInserted)));
            } catch (RuntimeException e) {
                outcomes.add(new AcceptOutcome.Refused(e));
            }
        }
        return outcomes;
    }

    private Transfer outcomeOf(
        TransferRequest request,
        @Nullable Transfer row,
        boolean isTheInsertedRequest,
        Map<UUID, Transfer> existing,
        Set<UUID> closed,
        Set<UUID> answeredAsInserted
    ) {
        if (row != null && isTheInsertedRequest && answeredAsInserted.add(request.transferId())) {
            return row;
        }
        if (row != null) {
            return repeat(row, request);
        }
        var found = existing.get(request.transferId());
        if (found != null) {
            return repeat(found, request);
        }
        if (closed.contains(request.runId())) {
            throw new PeriodClosedException(request.runId());
        }
        throw new IllegalStateException("Transfer " + request.transferId() + " was neither inserted nor found");
    }

    private static Map<UUID, Transfer> byTransferId(List<Transfer> rows) {
        var byId = new HashMap<UUID, Transfer>();
        rows.forEach(row -> byId.put(row.transferId(), row));
        return byId;
    }

    private Transfer repeat(Transfer existing, TransferRequest request) {
        if (!matches(existing, request)) {
            throw new IdempotencyConflictException(request.transferId());
        }
        repeated.increment();
        return existing;
    }

    /**
     * The only writer of results, one transaction per poll; a waiting client reads the committed row, so a final
     * status it has seen is never followed by an older one. Latency uses database timestamps only, because JVM and
     * database clocks may drift apart.
     */
    @Transactional
    public void recordResults(List<LedgerResult> results) {
        transfers.applyResults(results).forEach(applied -> latency.record(
            Duration.between(applied.acceptedAt(), Objects.requireNonNull(applied.resultedAt(), "resultedAt of an applied result"))));
    }

    public @Nullable Transfer find(UUID transferId) {
        return transfers.find(transferId);
    }

    @Transactional(readOnly = true)
    public RunSummary summarize(UUID runId) {
        return transfers.summarize(runId);
    }

    /** The period goes with the run; the answer reflects the transfers only. */
    @Transactional
    public boolean deleteRun(UUID runId) {
        periods.delete(runId);
        return transfers.deleteRun(runId) > 0;
    }

    private static boolean matches(Transfer transfer, TransferRequest request) {
        return transfer.runId().equals(request.runId()) && transfer.fromAccount() == request.fromAccount()
            && transfer.toAccount() == request.toAccount() && transfer.amountCents() == request.amountCents();
    }
}
