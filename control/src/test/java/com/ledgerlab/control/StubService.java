package com.ledgerlab.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import tools.jackson.databind.ObjectMapper;

final class StubService implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    volatile int status = 200;
    volatile Duration delay = Duration.ZERO;

    /** {@link #headers} by lowercased name. */
    record Call(String method, String path, String body, Map<String, String> headers) {
    }

    private record Answer(int status, String body, Duration delay) {
    }

    private final ConcurrentHashMap<String, Answer> answers = new ConcurrentHashMap<>();
    final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();

    private final HttpServer server;

    final String baseUrl;

    StubService() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        var headers = new HashMap<String, String>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        calls.add(new Call(method, path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), headers));
        var rawQuery = exchange.getRequestURI().getRawQuery();
        var answer = rawQuery == null ? null : answers.get(method + " " + path + "?" + URLDecoder.decode(rawQuery, StandardCharsets.UTF_8));
        if (answer == null) {
            answer = answers.get(method + " " + path);
        }
        try {
            Thread.sleep(answer != null ? answer.delay() : delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        var body = answer == null || answer.body() == null ? null : answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer != null ? answer.status() : status, body != null && body.length > 0 ? body.length : -1);
        if (body != null) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    void answer(String method, String path, int status) {
        answer(method, path, status, null);
    }

    void answer(String method, String path, int status, Object body) {
        answer(method, path, status, body, Duration.ZERO);
    }

    void answer(String method, String path, int status, Object body, Duration delay) {
        var text = body == null ? null : body instanceof String string ? string : MAPPER.writeValueAsString(body);
        answers.put(method + " " + path, new Answer(status, text, delay));
    }

    /** An answer to one query string only, e.g. one PromQL query; {@code query} is the whole query string, form-decoded as Prometheus reads it. */
    void answerQuery(String method, String path, String query, int status, Object body) {
        answer(method, path + "?" + query, status, body);
    }

    List<Call> callsTo(String method, String path) {
        return calls.stream().filter(it -> it.method().equals(method) && it.path().equals(path)).toList();
    }

    void reset() {
        status = 200;
        delay = Duration.ZERO;
        answers.clear();
        calls.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
