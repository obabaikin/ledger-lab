package com.ledgerlab.control.generator;

import static com.ledgerlab.control.http.ServiceHttp.forRun;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class GeneratorClient {

    private final ServiceHttp http;
    private final Duration reconciliationTimeout;

    public GeneratorClient(ControlProperties props, ObjectMapper objectMapper) {
        this.http = new ServiceHttp(props.generator().baseUrl(), props.services().requestTimeout(), objectMapper);
        this.reconciliationTimeout = props.generator().reconciliationTimeout();
    }

    public Reply<Object> start(StartSimulationRequest request) throws InterruptedException {
        return http.send("POST", GeneratorApi.SIMULATIONS_PATH, request);
    }

    public Reply<SimulationStatus> status(UUID runId) throws InterruptedException {
        return http.call("GET", forRun(GeneratorApi.SIMULATION_PATH, runId), SimulationStatus.class);
    }

    public Reply<Object> stop(UUID runId) throws InterruptedException {
        return http.send("POST", forRun(GeneratorApi.STOP_PATH, runId));
    }

    public Reply<ClientReconciliationReport> reconciliation(UUID runId) throws InterruptedException {
        return http.call("GET", forRun(GeneratorApi.RECONCILIATION_PATH, runId), ClientReconciliationReport.class, reconciliationTimeout);
    }

    public Reply<Object> releaseJournal(UUID runId) throws InterruptedException {
        return http.send("DELETE", forRun(GeneratorApi.JOURNAL_PATH, runId));
    }
}
