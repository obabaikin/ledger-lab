package com.ledgerlab.control.reporting;

import static com.ledgerlab.control.http.ServiceHttp.forPeriod;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReportingClient {

    private final ServiceHttp http;

    public ReportingClient(ControlProperties props, ObjectMapper objectMapper) {
        this.http = new ServiceHttp(props.reporting().baseUrl(), props.services().requestTimeout(), objectMapper);
    }

    public Reply<PeriodStats> stats(Instant periodStart) throws InterruptedException {
        return http.call("GET", forPeriod(ReportingApi.STATS_PATH, periodStart), PeriodStats.class);
    }
}
