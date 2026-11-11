package com.ledgerlab.control.api;

import com.ledgerlab.control.entry.SessionOverview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "What a session may do now.")
public record SessionView(
    UUID sessionId,
    @Schema(description = "False once the invite expired or was switched off; the session can then only look.")
    boolean inviteValid,
    @Schema(description = "Whether the chaos button works in this deployment.")
    boolean chaosAvailable,
    @Schema(description = "Seconds until chaos may be used again, on the whole cluster; 0 when it may be used now.")
    long chaosReadyInSeconds,
    @Schema(description = "Speeds a load pulse may set, in transfers a second, lowest first.")
    List<Integer> loadTpsOptions,
    @Schema(description = "The speed offered first.")
    int defaultLoadTps,
    @Schema(description = "Lengths a load pulse may last, in seconds, shortest first.")
    List<Integer> loadDurationOptions,
    @Schema(description = "The length offered first, in seconds.")
    int defaultLoadSeconds,
    @Schema(description = "Seconds until a load pulse may be started again, on the whole bank; 0 when it may be started now.")
    long loadReadyInSeconds
) {
    static SessionView of(SessionOverview overview) {
        return new SessionView(
            overview.sessionId(), overview.inviteValid(), overview.chaosAvailable(), overview.chaosReadyInSeconds(),
            overview.loadTpsOptions(), overview.defaultLoadTps(), overview.loadDurationOptions(), overview.defaultLoadSeconds(),
            overview.loadReadyInSeconds());
    }
}
