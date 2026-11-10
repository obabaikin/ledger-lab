package com.ledgerlab.control.runs;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.api.ControlErrors;
import com.ledgerlab.control.entry.InviteNotValidException;
import com.ledgerlab.control.entry.InviteRepository;
import com.ledgerlab.control.entry.SessionNotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunService {

    private final InviteRepository invites;
    private final RunRepository runs;
    private final ControlProperties props;

    public RunService(InviteRepository invites, RunRepository runs, ControlProperties props) {
        this.invites = invites;
        this.runs = runs;
        this.props = props;
    }

    @Transactional
    public Run start(UUID sessionId, RunRequest request) {
        var session = invites.findSession(sessionId);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        var requestId = request.requestId();
        if (requestId == null) {
            throw new InvalidRunRequestException(ControlErrors.REQUEST_ID_REQUIRED);
        }
        runs.lockQueue();
        var existing = runs.findByRequest(sessionId, requestId);
        if (existing != null) {
            if (!Objects.equals(existing.tps(), request.tps()) || !Objects.equals(existing.durationSeconds(), request.durationSeconds())) {
                throw new RequestIdConflictException();
            }
            return existing;
        }
        if (runs.hasRun(sessionId, RunState.UNFINISHED_STATES)) {
            throw new RunNotFinalException();
        }
        if (!props.runs().tpsOptions().contains(request.tps())) {
            throw new InvalidRunRequestException(ControlErrors.tpsNotAllowed(props.runs().tpsOptions()));
        }
        if (!props.runs().durationSecondsOptions().contains(request.durationSeconds())) {
            throw new InvalidRunRequestException(ControlErrors.durationNotAllowed(props.runs().durationSecondsOptions()));
        }
        var invite = invites.find(session.token());
        if (invite == null || !invite.valid()) {
            throw new InviteNotValidException();
        }
        var usage = runs.quotaUsage(session.token(), Run.QUOTA_WINDOW, invite.quota());
        if (usage.used() >= invite.quota()) {
            throw new QuotaExceededException(usage.retryAfterSeconds());
        }
        if (runs.countIn(List.of(RunState.QUEUED)) >= props.runs().maxQueued()) {
            throw new QueueFullException();
        }
        var runId = runs.insertQueued(sessionId, session.token(), requestId, request);
        return Objects.requireNonNull(runs.find(sessionId, runId), "the run just queued");
    }

    @Transactional(readOnly = true)
    public Run find(UUID sessionId, UUID runId) {
        if (invites.findSession(sessionId) == null) {
            throw new SessionNotFoundException();
        }
        var run = runs.find(sessionId, runId);
        if (run == null) {
            throw new RunNotFoundException();
        }
        return run;
    }

    /** The answer's run is RUNNING when the stop was taken; any later state means the run had already ended. */
    @Transactional
    public Run stop(UUID sessionId, UUID runId) {
        if (invites.findSession(sessionId) == null) {
            throw new SessionNotFoundException();
        }
        var run = runs.findForUpdate(sessionId, runId);
        if (run == null) {
            throw new RunNotFoundException();
        }
        switch (run.state()) {
            case QUEUED, CREATED -> throw new RunNotStartedException();
            case RUNNING -> runs.requestStop(runId);
            default -> {
            }
        }
        return Objects.requireNonNull(runs.find(sessionId, runId), "the run just stopped");
    }

    @Transactional(readOnly = true)
    public RunReport report(UUID sessionId, UUID runId) {
        var run = find(sessionId, runId);
        if (run.state() != RunState.RECONCILED) {
            throw new ReportNotReadyException();
        }
        return Objects.requireNonNull(runs.reportOf(runId), "the report of a reconciled run");
    }

    @Transactional(readOnly = true)
    public List<Run> listOf(UUID sessionId) {
        if (invites.findSession(sessionId) == null) {
            throw new SessionNotFoundException();
        }
        return runs.listOf(sessionId);
    }
}
