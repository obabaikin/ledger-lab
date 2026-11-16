package com.ledgerlab.gateway.service;

import com.ledgerlab.gateway.GatewayProperties;
import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.persistence.PeriodRepository;
import com.ledgerlab.gateway.persistence.TransferKey;
import com.ledgerlab.gateway.persistence.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private final PeriodRepository periods;
    private final Duration idempotencyWindow;
    private final Clock clock;
    private final Counter accepted;
    private final Counter repeated;
    private final Timer latency;

    public TransferService(TransferRepository transfers, PeriodRepository periods, GatewayProperties props, Clock clock, MeterRegistry registry) {
        this.transfers = transfers;
        this.periods = periods;
        this.idempotencyWindow = props.periods().retention();
        this.clock = clock;
        this.accepted = Counter.builder("gateway.transfers").tag("outcome", "accepted").register(registry);
        this.repeated = Counter.builder("gateway.transfers").tag("outcome", "repeated").register(registry);
        this.latency = TransferLatency.timer(registry, props.latency().sloThresholds());
    }

    /**
     * One transaction and one insert for many requests; each gets, in the same order, the outcome it would have had
     * alone: its row, or {@link IdempotencyConflictException}. The responses go out after the commit, before Kafka is
     * involved.
     */
    @Transactional
    public List<AcceptOutcome> acceptAll(List<TransferRequest> requests) {
        periods.lockAccepting();
        // One instant for the insert and the lookup: a key is inside the window for both or for neither.
        var keysSince = keysSince();
        var inserting = new LinkedHashMap<UUID, TransferRequest>();
        requests.forEach(request -> inserting.putIfAbsent(request.transferId(), request));
        var inserted = byTransferId(transfers.insertAllIfAbsent(List.copyOf(inserting.values()), keysSince));
        accepted.increment(inserted.size());
        var notInserted = new LinkedHashSet<UUID>();
        for (var request : requests) {
            if (!inserted.containsKey(request.transferId())) {
                notInserted.add(request.transferId());
            }
        }
        var existing = byTransferId(transfers.findAll(notInserted, keysSince));
        var answeredAsInserted = new HashSet<UUID>();
        var outcomes = new ArrayList<AcceptOutcome>(requests.size());
        for (var request : requests) {
            try {
                var row = inserted.get(request.transferId());
                outcomes.add(new AcceptOutcome.Accepted(
                    outcomeOf(request, row, request.equals(inserting.get(request.transferId())), existing, answeredAsInserted)));
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

    /** By the key alone, as a client asks; a transfer older than the idempotency window is not found. */
    public @Nullable Transfer find(UUID transferId) {
        return transfers.find(transferId, keysSince());
    }

    public @Nullable Transfer find(TransferKey key) {
        return transfers.find(key);
    }

    private Instant keysSince() {
        return clock.instant().minus(idempotencyWindow);
    }

    private static boolean matches(Transfer transfer, TransferRequest request) {
        return transfer.fromAccount() == request.fromAccount()
            && transfer.toAccount() == request.toAccount() && transfer.amountCents() == request.amountCents();
    }
}
