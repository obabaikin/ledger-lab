package com.ledgerlab.control.generator;

import static com.ledgerlab.control.http.ServiceHttp.forPeriod;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class GeneratorClient {

    private final ServiceHttp http;

    public GeneratorClient(ControlProperties props, ObjectMapper objectMapper) {
        this.http = new ServiceHttp(props.generator().baseUrl(), props.services().requestTimeout(), objectMapper);
    }

    public Reply<LoadStatus> load() throws InterruptedException {
        return http.call("GET", GeneratorApi.LOAD_PATH, LoadStatus.class);
    }

    public Reply<MinutesPage> minutesAfter(@Nullable Instant after) throws InterruptedException {
        var query = after == null ? "" : "?" + GeneratorApi.MINUTES_AFTER + "=" + URLEncoder.encode(after.toString(), StandardCharsets.UTF_8);
        return http.call("GET", GeneratorApi.MINUTES_PATH + query, MinutesPage.class);
    }

    public Reply<ClientReconciliation> reconciliation(Instant periodStart) throws InterruptedException {
        return http.call("GET", forPeriod(GeneratorApi.RECONCILIATION_PATH, periodStart), ClientReconciliation.class);
    }

    public Reply<ClientReconciliation> startReconciliation(Instant periodStart) throws InterruptedException {
        return http.call("POST", forPeriod(GeneratorApi.RECONCILIATION_PATH, periodStart), ClientReconciliation.class);
    }
}
