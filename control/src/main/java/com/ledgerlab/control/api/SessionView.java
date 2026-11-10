package com.ledgerlab.control.api;

import com.ledgerlab.control.entry.SessionOverview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "What a session may do now.")
public record SessionView(
    UUID sessionId,
    @Schema(description = "False once the invite expired or was switched off; runs can then only be read.")
    boolean inviteValid,
    @Schema(description = "Runs the invite may start in any hour.")
    int runsPerHour,
    @Schema(description = "Runs started with the invite in the last hour, from any session.")
    long runsLastHour,
    @Schema(description = "Allowed transfers per second.")
    List<Integer> tpsOptions,
    @Schema(description = "Transfers per second chosen when the page opens; one of tpsOptions.")
    int defaultTps,
    @Schema(description = "Allowed run durations, seconds.")
    List<Integer> durationOptions,
    @Schema(description = "Whether the chaos button works in this deployment.")
    boolean chaosAvailable,
    @Schema(description = "Seconds until chaos may be used again, on the whole cluster; 0 when it may be used now.")
    long chaosReadyInSeconds
) {
    static SessionView of(SessionOverview overview) {
        return new SessionView(
            overview.sessionId(), overview.inviteValid(), overview.runsPerHour(), overview.runsLastHour(), overview.tpsOptions(),
            overview.defaultTps(), overview.durationOptions(), overview.chaosAvailable(), overview.chaosReadyInSeconds());
    }
}
