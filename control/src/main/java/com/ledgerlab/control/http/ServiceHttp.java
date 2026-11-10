package com.ledgerlab.control.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class ServiceHttp {

    private static final int SUCCESS_FROM = 200;
    private static final int SUCCESS_TO = 299;
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String JSON = "application/json";
    private static final String ERROR_FIELD = "error";
    private static final String RUN_ID_VARIABLE = "{runId}";

    private final String baseUrl;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final Map<String, String> headers;
    private final HttpClient http;

    public ServiceHttp(String baseUrl, Duration timeout, ObjectMapper objectMapper) {
        this(baseUrl, timeout, objectMapper, Map.of());
    }

    public ServiceHttp(String baseUrl, Duration timeout, ObjectMapper objectMapper, Map<String, String> headers) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.headers = headers;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static String forRun(String path, UUID runId) {
        return path.replace(RUN_ID_VARIABLE, runId.toString());
    }

    public <T> Reply<T> call(String method, String path, Class<T> answer) throws InterruptedException {
        return call(method, path, answer, null, timeout);
    }

    public <T> Reply<T> call(String method, String path, Class<T> answer, Duration timeout) throws InterruptedException {
        return call(method, path, answer, null, timeout);
    }

    public <T> Reply<T> call(String method, String path, Class<T> answer, Object body) throws InterruptedException {
        return call(method, path, answer, body, timeout);
    }

    public <T> Reply<T> call(String method, String path, @Nullable Class<T> answer, @Nullable Object body, Duration timeout)
        throws InterruptedException {
        var publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header(CONTENT_TYPE, JSON);
        headers.forEach(builder::header);
        var request = builder.method(method, publisher).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException | HttpConnectTimeoutException e) {
            return new Reply.NotConnected<>();
        } catch (IOException e) {
            return new Reply.NoAnswer<>();
        }
        var status = response.statusCode();
        if (status < SUCCESS_FROM || status > SUCCESS_TO) {
            var error = errorOf(response.body());
            return new Reply.Refused<>(status, error != null ? error : "HTTP " + status);
        }
        if (answer == null) {
            return new Reply.Answered<>(status, null);
        }
        T read;
        try {
            read = objectMapper.readValue(response.body(), answer);
        } catch (JacksonException e) {
            read = null;
        }
        return read == null ? new Reply.Unreadable<>() : new Reply.Answered<>(status, read);
    }

    public Reply<Object> send(String method, String path) throws InterruptedException {
        return send(method, path, null);
    }

    public Reply<Object> send(String method, String path, @Nullable Object body) throws InterruptedException {
        return call(method, path, null, body, timeout);
    }

    private @Nullable String errorOf(@Nullable String body) {
        try {
            var error = objectMapper.readTree(body).get(ERROR_FIELD);
            return error == null ? null : error.asString();
        } catch (Throwable e) {
            return null;
        }
    }
}
