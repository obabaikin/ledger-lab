package com.ledgerlab.control.api;

import com.ledgerlab.control.entry.SessionOverview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "What a session may do now.")
public record SessionView(
    UUID sessionId,
    @Schema(description = "False once the invite expired or was switched off; the session can then only look.")
    boolean inviteValid,
    @Schema(description = "Whether the chaos button works in this deployment.")
    boolean chaosAvailable,
    @Schema(description = "Seconds until chaos may be used again, on the whole cluster; 0 when it may be used now.")
    long chaosReadyInSeconds
) {
    static SessionView of(SessionOverview overview) {
        return new SessionView(overview.sessionId(), overview.inviteValid(), overview.chaosAvailable(), overview.chaosReadyInSeconds());
    }
}
