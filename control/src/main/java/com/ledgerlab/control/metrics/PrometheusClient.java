package com.ledgerlab.control.metrics;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PrometheusClient {

    private final ServiceHttp http;

    public PrometheusClient(ControlProperties props, ObjectMapper objectMapper) {
        this.http = new ServiceHttp(props.prometheus().baseUrl(), props.prometheus().requestTimeout(), objectMapper);
    }

    public @Nullable List<PrometheusSample> query(String query) throws InterruptedException {
        var reply = http.call("GET", PrometheusApi.QUERY_PATH + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8), PrometheusResponse.class);
        if (!(reply instanceof Reply.Answered<PrometheusResponse>(var status, var body)) || body == null) {
            return null;
        }
        var data = body.data();
        return PrometheusApi.SUCCESS.equals(body.status()) && data != null ? data.result() : null;
    }
}
