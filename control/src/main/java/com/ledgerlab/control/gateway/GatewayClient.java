package com.ledgerlab.control.gateway;

import static com.ledgerlab.control.http.ServiceHttp.forRun;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class GatewayClient {

    private final ServiceHttp http;
    private final Duration summaryTimeout;

    public GatewayClient(ControlProperties props, ObjectMapper objectMapper) {
        this.http = new ServiceHttp(props.gateway().baseUrl(), props.services().requestTimeout(), objectMapper);
        this.summaryTimeout = props.gateway().summaryTimeout();
    }

    public Reply<RunSummary> summary(UUID runId) throws InterruptedException {
        return http.call("GET", forRun(GatewayApi.SUMMARY_PATH, runId), RunSummary.class, summaryTimeout);
    }

    public Reply<ClosedPeriod> close(UUID runId) throws InterruptedException {
        return http.call("POST", forRun(GatewayApi.CLOSE_PATH, runId), ClosedPeriod.class);
    }

    public Reply<Object> delete(UUID runId) throws InterruptedException {
        return http.send("DELETE", forRun(GatewayApi.RUN_PATH, runId));
    }
}
