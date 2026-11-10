package com.ledgerlab.control.ledger;

import static com.ledgerlab.control.http.ServiceHttp.forRun;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LedgerClient {

    private final ServiceHttp http;
    private final Duration reconciliationTimeout;

    public LedgerClient(ControlProperties props, ObjectMapper objectMapper) {
        this.http = new ServiceHttp(props.ledger().baseUrl(), props.services().requestTimeout(), objectMapper);
        this.reconciliationTimeout = props.ledger().reconciliationTimeout();
    }

    public Reply<Object> openAccounts(UUID runId, OpenAccountsRequest request) throws InterruptedException {
        return http.send("POST", forRun(LedgerApi.ACCOUNTS_PATH, runId), request);
    }

    public Reply<ReconciliationReport> reconciliation(UUID runId) throws InterruptedException {
        return http.call("GET", forRun(LedgerApi.RECONCILIATION_PATH, runId), ReconciliationReport.class, reconciliationTimeout);
    }

    public Reply<Object> delete(UUID runId) throws InterruptedException {
        return http.send("DELETE", forRun(LedgerApi.RUN_PATH, runId));
    }
}
