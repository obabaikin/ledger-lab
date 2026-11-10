package com.ledgerlab.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

@ConfigurationProperties("gateway")
public record GatewayProperties(
    String transfersTopic,
    String resultsTopic,
    Outbox outbox,
    Consumer consumer,
    Duration maxWait,
    WaitRead waitRead,
    Duration retryAfter,
    // How long closing a period waits for inserts in flight; meanwhile new submissions of the run wait too.
    Duration closeLockTimeout,
    Inflight inflight,
    Admission admission,
    Accept accept,
    WarmUp warmUp
) {
    /**
     * Up to {@link #rounds} rounds of the submission path. The gateway takes traffic after at most {@link #duration}; the rounds go
     * on behind it for at most {@link #maxDuration}, and only then does it report itself warm (control admits runs after that).
     */
    public record WarmUp(int rounds, Duration duration, Duration maxDuration) {
    }

    /** At most {@link #maxConcurrent} submissions are open at once, waiting for the result included; one more is refused. */
    public record Inflight(int maxConcurrent) {
    }

    /** At most {@link #maxConcurrent} submissions insert at once; one more waits up to {@link #waitLimit}, then is refused. */
    public record Admission(int maxConcurrent, @Name("wait") Duration waitLimit) {
    }

    /** Each of {@link #writers} commits up to {@link #batchSize} submissions per transaction; a request waits up to {@link #answerTimeout}. */
    public record Accept(int batchSize, int writers, Duration answerTimeout) {
    }

    /** A woken request reads its row after {@link #firstPause}, then after twice the last pause, at most {@link #maxReads} times. */
    public record WaitRead(Duration firstPause, int maxReads) {
    }

    public record Outbox(int batchSize, Duration sendTimeout) {
    }

    public record Consumer(Duration retryBackoff) {
    }
}
