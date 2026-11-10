package com.ledgerlab.control.entry;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.chaos.ChaosService;
import com.ledgerlab.control.runs.Run;
import com.ledgerlab.control.runs.RunRepository;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntryService {

    private final InviteRepository invites;
    private final RunRepository runs;
    private final ChaosService chaos;
    private final ControlProperties props;

    public EntryService(InviteRepository invites, RunRepository runs, ChaosService chaos, ControlProperties props) {
        this.invites = invites;
        this.runs = runs;
        this.chaos = chaos;
        this.props = props;
    }

    /** Null rather than an exception: an exception would roll back the visit of an expired invite. */
    @Transactional
    public @Nullable UUID enter(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        var invite = invites.find(token);
        if (invite == null) {
            return null;
        }
        invites.recordVisit(token);
        return invite.valid() ? invites.createSession(token) : null;
    }

    @Transactional(readOnly = true)
    public SessionOverview overview(UUID sessionId) {
        var session = invites.findSession(sessionId);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        var invite = Objects.requireNonNull(invites.find(session.token()), "the invite of a session");
        return new SessionOverview(
            sessionId,
            invite.valid(),
            invite.quota(),
            runs.quotaUsage(session.token(), Run.QUOTA_WINDOW, invite.quota()).used(),
            props.runs().tpsOptions(),
            props.runs().defaultTps(),
            props.runs().durationSecondsOptions(),
            props.chaos().enabled(),
            chaos.readyInSeconds());
    }
}
