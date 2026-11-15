package com.ledgerlab.control.ledger;

import static com.ledgerlab.control.http.ServiceHttp.forPeriod;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LedgerClient {

    private final ServiceHttp http;
    private final Duration countTimeout;

    public LedgerClient(ControlProperties props, ObjectMapper objectMapper) {
        this.http = new ServiceHttp(props.ledger().baseUrl(), props.services().requestTimeout(), objectMapper);
        this.countTimeout = props.periods().countTimeout();
    }

    public Reply<LedgerReconciliation> reconciliation(Instant periodStart) throws InterruptedException {
        return http.call("GET", forPeriod(LedgerApi.RECONCILIATION_PATH, periodStart), LedgerReconciliation.class, countTimeout);
    }

    public Reply<LedgerCheckState> check(Instant periodStart) throws InterruptedException {
        return http.call("GET", forPeriod(LedgerApi.CHECK_PATH, periodStart), LedgerCheckState.class);
    }

    public Reply<Object> requestCheck(Instant periodStart) throws InterruptedException {
        return http.send("POST", forPeriod(LedgerApi.CHECK_PATH, periodStart));
    }

    public Reply<Books> books() throws InterruptedException {
        return http.call("GET", LedgerApi.BOOKS_PATH, Books.class);
    }
}
