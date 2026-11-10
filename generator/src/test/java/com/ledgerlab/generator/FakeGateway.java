package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.Statement;
import com.ledgerlab.generator.bank.StatementLine;
import com.ledgerlab.generator.bank.SubmitTransferRequest;
import com.ledgerlab.generator.bank.TransferStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal stand-in for the gateway's POST /transfers, with an optional first-attempt failure, a refusal,
 * a slow answer, a slow answer to repeats of an id, a ledger rejection for waited requests, and a 200
 * with a body as a newer gateway might send it. Its statement lists every transfer it answered with a
 * 2xx, as the ledger has settled them, in pages of {@link Options#statementPage}.
 */
final class FakeGateway implements AutoCloseable {

    // A test opens hundreds of connections at once; the system's default queue drops some, and the client gives up on them.
    private static final int CONNECTION_BACKLOG = 1_024;
    private static final ObjectMapper ANSWER_MAPPER = new ObjectMapper();

    static final class Options {
        private boolean failFirstAttempt = false;
        private Integer refuseWith = null;
        /** The first attempts of every transfer are refused as busy, as an overloaded gateway does. */
        private int busyAttempts = 0;
        private int busyRetryAfterSeconds = 0;
        private Duration responseDelay = Duration.ZERO;
        private RejectReason rejectReason = null;
        private Duration repeatDelay = Duration.ZERO;
        private Function<SubmitTransferRequest, String> rawAnswer = null;
        private boolean periodClosed = true;
        private int statementPage = 1_000;
        /** Status code to fail the given statement page with (numbered from 1), or null to serve it. */
        private IntFunction<Integer> statementFailure = page -> null;
        /** Served with 200 instead of the statement, as a broken gateway might. */
        private String rawStatement = null;
        /** Changes a statement page (numbered from 1) before it is served, as a broken gateway might. */
        private BiFunction<Integer, Statement, Statement> tamperStatement = (page, statement) -> statement;

        Options failFirstAttempt() {
            this.failFirstAttempt = true;
            return this;
        }

        Options refuseWith(int status) {
            this.refuseWith = status;
            return this;
        }

        Options busyAttempts(int attempts) {
            this.busyAttempts = attempts;
            return this;
        }

        Options busyRetryAfterSeconds(int seconds) {
            this.busyRetryAfterSeconds = seconds;
            return this;
        }

        Options responseDelay(Duration delay) {
            this.responseDelay = delay;
            return this;
        }

        Options rejectReason(RejectReason reason) {
            this.rejectReason = reason;
            return this;
        }

        Options repeatDelay(Duration delay) {
            this.repeatDelay = delay;
            return this;
        }

        Options rawAnswer(Function<SubmitTransferRequest, String> answer) {
            this.rawAnswer = answer;
            return this;
        }

        Options periodNotClosed() {
            this.periodClosed = false;
            return this;
        }

        Options statementPage(int lines) {
            this.statementPage = lines;
            return this;
        }

        Options statementFailure(IntFunction<Integer> failure) {
            this.statementFailure = failure;
            return this;
        }

        Options rawStatement(String body) {
            this.rawStatement = body;
            return this;
        }

        Options tamperStatement(BiFunction<Integer, Statement, Statement> tamper) {
            this.tamperStatement = tamper;
            return this;
        }

        FakeGateway start() {
            return new FakeGateway(this);
        }
    }

    private final Options options;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpServer server;
    final ConcurrentHashMap<String, AtomicInteger> attemptsById = new ConcurrentHashMap<>();
    final AtomicInteger requests = new AtomicInteger();
    volatile CountDownLatch answersHeld = null;
    private final LinkedHashMap<UUID, StatementLine> accepted = new LinkedHashMap<>();
    private final AtomicInteger statementRequests = new AtomicInteger();

    static Options with() {
        return new Options();
    }

    FakeGateway() {
        this(new Options());
    }

    private FakeGateway(Options options) {
        this.options = options;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), CONNECTION_BACKLOG);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(GatewayApi.TRANSFERS_PATH, this::answerSubmission);
        server.createContext("/v1/runs/", this::answerStatement);
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    List<Integer> attempts() {
        return attemptsById.values().stream().map(AtomicInteger::get).toList();
    }

    private void answerSubmission(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        var held = answersHeld;
        try {
            if (held != null) {
                held.await();
            }
            var request = objectMapper.readValue(exchange.getRequestBody().readAllBytes(), SubmitTransferRequest.class);
            var id = request.transferId().toString();
            var attempt = attemptsById.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            var query = exchange.getRequestURI().getQuery();
            var waited = query != null && query.contains(GatewayApi.WAIT_PARAM + "=");
            Thread.sleep(attempt > GatewayClient.FIRST_ATTEMPT ? options.repeatDelay : options.responseDelay);
            int code;
            String response;
            if (attempt <= options.busyAttempts) {
                exchange.getResponseHeaders().add(HttpHeaders.RETRY_AFTER, Integer.toString(options.busyRetryAfterSeconds));
                code = HttpStatus.SERVICE_UNAVAILABLE.value();
                response = "{\"error\":\"" + GatewayApi.BUSY_ERROR + "\"}";
            } else if (options.failFirstAttempt && attempt == GatewayClient.FIRST_ATTEMPT) {
                code = HttpStatus.SERVICE_UNAVAILABLE.value();
                response = "{\"error\":\"unavailable\"}";
            } else if (options.refuseWith != null) {
                code = options.refuseWith;
                response = "{\"error\":\"refused\"}";
            } else if (options.rawAnswer != null) {
                code = HttpStatus.OK.value();
                response = options.rawAnswer.apply(request);
            } else if (waited && options.rejectReason != null) {
                code = HttpStatus.OK.value();
                response = bankAnswer(request, TransferStatus.REJECTED, options.rejectReason);
            } else if (waited) {
                code = HttpStatus.OK.value();
                response = bankAnswer(request, TransferStatus.POSTED, null);
            } else {
                code = HttpStatus.ACCEPTED.value();
                response = bankAnswer(request, TransferStatus.ACCEPTED, null);
            }
            if (HttpStatus.valueOf(code).is2xxSuccessful()) {
                record(request);
            }
            respond(exchange, code, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void answerStatement(HttpExchange exchange) throws IOException {
        var query = exchange.getRequestURI().getQuery();
        var after = query == null ? null : Arrays.stream(query.split("&"))
            .filter(it -> it.startsWith(GatewayApi.AFTER_PARAM + "="))
            .map(it -> UUID.fromString(it.substring(it.indexOf('=') + 1)))
            .findFirst().orElse(null);
        var pageNumber = statementRequests.incrementAndGet();
        var failure = options.statementFailure.apply(pageNumber);
        if (failure != null) {
            respond(exchange, failure, "{\"error\":\"failed\"}");
        } else if (!options.periodClosed) {
            respond(exchange, HttpStatus.CONFLICT.value(), "{\"error\":\"period not closed\"}");
        } else if (options.rawStatement != null) {
            respond(exchange, HttpStatus.OK.value(), options.rawStatement);
        } else {
            respond(exchange, HttpStatus.OK.value(), objectMapper.writeValueAsString(options.tamperStatement.apply(pageNumber, statement(after))));
        }
    }

    private void record(SubmitTransferRequest request) {
        synchronized (accepted) {
            accepted.putIfAbsent(
                request.transferId(),
                new StatementLine(
                    request.transferId(), request.fromAccount(), request.toAccount(), request.amountCents(),
                    options.rejectReason != null ? TransferStatus.REJECTED : TransferStatus.POSTED,
                    options.rejectReason));
        }
    }

    private Statement statement(UUID after) {
        synchronized (accepted) {
            var lines = new ArrayList<>(accepted.values());
            var from = 0;
            if (after != null) {
                for (var i = 0; i < lines.size(); i++) {
                    if (lines.get(i).transferId().equals(after)) {
                        from = i + 1;
                    }
                }
            }
            var page = lines.subList(from, Math.min(lines.size(), from + options.statementPage));
            var next = from + page.size() < lines.size() ? page.getLast().transferId() : null;
            return new Statement(lines.size(), page, next);
        }
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Shaped as gateway/openapi.json's TransferResponse, timestamps included, though the generator reads fewer fields. */
    static String bankAnswer(SubmitTransferRequest request, TransferStatus status, RejectReason reason) {
        var now = Instant.now().toString();
        var answer = new HashMap<String, Object>();
        answer.put("transferId", request.transferId());
        answer.put("runId", request.runId());
        answer.put("status", status);
        answer.put("rejectReason", reason);
        answer.put("acceptedAt", now);
        answer.put("resultedAt", status.isFinal() ? now : null);
        return ANSWER_MAPPER.writeValueAsString(answer);
    }
}
