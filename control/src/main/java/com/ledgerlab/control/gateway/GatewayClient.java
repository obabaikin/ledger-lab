package com.ledgerlab.control.gateway;

import static com.ledgerlab.control.http.ServiceHttp.forPeriod;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class GatewayClient {

    private final ServiceHttp http;
    private final Duration countTimeout;

    public GatewayClient(ControlProperties props, ObjectMapper objectMapper) {
        this.http = new ServiceHttp(props.gateway().baseUrl(), props.services().requestTimeout(), objectMapper);
        this.countTimeout = props.periods().countTimeout();
    }

    public Reply<PeriodSummary> summary(Instant periodStart) throws InterruptedException {
        return http.call("GET", forPeriod(GatewayApi.SUMMARY_PATH, periodStart), PeriodSummary.class, countTimeout);
    }

    public Reply<ClosedPeriod> close(Instant periodStart) throws InterruptedException {
        return http.call("POST", forPeriod(GatewayApi.CLOSE_PATH, periodStart), ClosedPeriod.class);
    }
}
