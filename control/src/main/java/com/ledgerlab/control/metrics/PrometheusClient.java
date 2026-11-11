package com.ledgerlab.control.metrics;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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

    /** Null if Prometheus could not be read; an empty list if the query has no series in the range. */
    public @Nullable List<PrometheusRange> queryRange(String query, Instant start, Instant end, Duration step) throws InterruptedException {
        var path = PrometheusApi.QUERY_RANGE_PATH + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&start=" + start.getEpochSecond() + "&end=" + end.getEpochSecond() + "&step=" + step.toSeconds();
        var reply = http.call("GET", path, PrometheusRangeResponse.class);
        if (!(reply instanceof Reply.Answered<PrometheusRangeResponse>(var status, var body)) || body == null) {
            return null;
        }
        var data = body.data();
        return PrometheusApi.SUCCESS.equals(body.status()) && data != null ? data.result() : null;
    }
}
