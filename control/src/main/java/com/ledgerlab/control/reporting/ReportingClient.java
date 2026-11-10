package com.ledgerlab.control.reporting;

import static com.ledgerlab.control.http.ServiceHttp.forRun;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReportingClient {

    private final ServiceHttp http;
    private final Duration finalStatsTimeout;

    public ReportingClient(ControlProperties props, ObjectMapper objectMapper) {
        this.http = new ServiceHttp(props.reporting().baseUrl(), props.services().requestTimeout(), objectMapper);
        this.finalStatsTimeout = props.reporting().finalStatsTimeout();
    }

    public Reply<RunStats> stats(UUID runId) throws InterruptedException {
        return http.call("GET", forRun(ReportingApi.STATS_PATH, runId), RunStats.class);
    }

    /** For the report: read once the run is drained, so it may wait as long as the books do. */
    public Reply<RunStats> finalStats(UUID runId) throws InterruptedException {
        return http.call("GET", forRun(ReportingApi.STATS_PATH, runId), RunStats.class, finalStatsTimeout);
    }

    public Reply<Object> delete(UUID runId) throws InterruptedException {
        return http.send("DELETE", forRun(ReportingApi.RUN_PATH, runId));
    }
}
