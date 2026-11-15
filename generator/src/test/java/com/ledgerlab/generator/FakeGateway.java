package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.Statement;
import com.ledgerlab.generator.bank.StatementLine;
import com.ledgerlab.generator.bank.SubmitTransferRequest;
import com.ledgerlab.generator.bank.TransferStatus;
import com.ledgerlab.generator.gateway.GatewayApi;
import com.ledgerlab.generator.gateway.GatewayClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal stand-in for the gateway's public API (gateway/openapi.json): submissions with an optional first-attempt
 * failure, a refusal, a slow answer, a slow answer to repeats of an id, a ledger rejection for waited requests, and a
 * 200 with a body as a newer gateway might send it; a transfer's status; and a period's statement, which lists every
 * transfer accepted in the period, as the ledger has settled them, in pages of {@link Options#statementPage}.
 */
public final class FakeGateway implements AutoCloseable {

    // A test opens hundreds of connections at once; the system's default queue drops some, and the client gives up on them.
    private static final int CONNECTION_BACKLOG = 1_024;
    private static final String STATEMENT_PREFIX = "/v2/periods/";
    private static final String STATEMENT_SUFFIX = "/statement";
    private static final ObjectMapper ANSWER_MAPPER = new ObjectMapper();

    public static final class Options {
        private boolean failFirstAttempt = false;
        private Integer refuseWith = null;
        /** The first attempts of every transfer are refused as busy, as an overloaded gateway does. */
        private int busyAttempts = 0;
        /** The first attempts of every transfer are refused as stopping, as an instance shutting down does. */
        private int stoppingAttempts = 0;
        private int retryAfterSeconds = 0;
        private Duration responseDelay = Duration.ZERO;
        private RejectReason rejectReason = null;
        private Duration repeatDelay = Duration.ZERO;
        private Function<SubmitTransferRequest, String> rawAnswer = null;
        /** Runs when a submission has arrived, before it is answered. */
        private Consumer<SubmitTransferRequest> onArrival = request -> { };
        private Duration periodLength = Duration.ofHours(1);
        private boolean periodClosed = true;
        private int statementPage = 1_000;
        /** Status code to fail the given statement request with (numbered from 1), or null to serve it. */
        private IntFunction<Integer> statementFailure = request -> null;
        /** Served with 200 instead of the statement, as a broken gateway might. */
        private String rawStatement = null;
        /** Changes a statement page (by the number of its request, from 1) before it is served, as a broken gateway might. */
        private BiFunction<Integer, Statement, Statement> tamperStatement = (request, statement) -> statement;
        /** Status code to answer every question about a transfer with, or null to answer it. */
        private Integer transferFailure = null;

        public Options failFirstAttempt() {
            this.failFirstAttempt = true;
            return this;
        }

        public Options refuseWith(int status) {
            this.refuseWith = status;
            return this;
        }

        public Options busyAttempts(int attempts) {
            this.busyAttempts = attempts;
            return this;
        }

        public Options stoppingAttempts(int attempts) {
            this.stoppingAttempts = attempts;
            return this;
        }

        public Options retryAfterSeconds(int seconds) {
            this.retryAfterSeconds = seconds;
            return this;
        }

        public Options responseDelay(Duration delay) {
            this.responseDelay = delay;
            return this;
        }

        public Options rejectReason(RejectReason reason) {
            this.rejectReason = reason;
            return this;
        }

        public Options repeatDelay(Duration delay) {
            this.repeatDelay = delay;
            return this;
        }

        public Options rawAnswer(Function<SubmitTransferRequest, String> answer) {
            this.rawAnswer = answer;
            return this;
        }

        public Options onArrival(Consumer<SubmitTransferRequest> action) {
            this.onArrival = action;
            return this;
        }

        public Options periodLength(Duration length) {
            this.periodLength = length;
            return this;
        }

        public Options periodNotClosed() {
            this.periodClosed = false;
            return this;
        }

        public Options statementPage(int lines) {
            this.statementPage = lines;
            return this;
        }

        public Options statementFailure(IntFunction<Integer> failure) {
            this.statementFailure = failure;
            return this;
        }

        public Options rawStatement(String body) {
            this.rawStatement = body;
            return this;
        }

        public Options tamperStatement(BiFunction<Integer, Statement, Statement> tamper) {
            this.tamperStatement = tamper;
            return this;
        }

        public Options transferFailure(int status) {
            this.transferFailure = status;
            return this;
        }

        public FakeGateway start() {
            return new FakeGateway(this);
        }
    }

    /** A transfer the bank has. */
    record Accepted(StatementLine line, Instant acceptedAt) {
    }

    private volatile Options options;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpServer server;
    public final ConcurrentHashMap<String, AtomicInteger> attemptsById = new ConcurrentHashMap<>();
    public final AtomicInteger requests = new AtomicInteger();
    public volatile CountDownLatch answersHeld = null;
    public final ConcurrentHashMap<UUID, Accepted> accepted = new ConcurrentHashMap<>();
    public final AtomicInteger statementRequests = new AtomicInteger();
    public final AtomicInteger transferQuestions = new AtomicInteger();

    public static Options with() {
        return new Options();
    }

    public FakeGateway() {
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
        server.createContext(GatewayApi.TRANSFERS_PATH, this::answerTransfers);
        server.createContext(STATEMENT_PREFIX, this::answerStatement);
        server.start();
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public List<Integer> attempts() {
        return attemptsById.values().stream().map(AtomicInteger::get).toList();
    }

    /** A bank that has seen nothing, behaving as the options say; for a gateway shared by the tests of a class. */
    public void reset(Options behaviour) {
        options = behaviour;
        attemptsById.clear();
        accepted.clear();
        requests.set(0);
        statementRequests.set(0);
        transferQuestions.set(0);
    }

    /** Puts a transfer into the bank as if it had been accepted then. */
    public void accept(UUID transferId, int from, int to, long amountCents, TransferStatus status, RejectReason reason, Instant acceptedAt) {
        accepted.put(transferId, new Accepted(new StatementLine(transferId, from, to, amountCents, status, reason), acceptedAt));
    }

    private void answerTransfers(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("GET")) {
            answerTransfer(exchange);
        } else {
            answerSubmission(exchange);
        }
    }

    private void answerSubmission(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        var held = answersHeld;
        var behaviour = options;
        try {
            if (held != null) {
                held.await();
            }
            var request = objectMapper.readValue(exchange.getRequestBody().readAllBytes(), SubmitTransferRequest.class);
            behaviour.onArrival.accept(request);
            var id = request.transferId().toString();
            var attempt = attemptsById.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            var query = exchange.getRequestURI().getQuery();
            var waited = query != null && query.contains(GatewayApi.WAIT_PARAM + "=");
            Thread.sleep(attempt > GatewayClient.FIRST_ATTEMPT ? behaviour.repeatDelay : behaviour.responseDelay);
            var acceptedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
            int code;
            String response;
            if (attempt <= behaviour.busyAttempts || attempt <= behaviour.stoppingAttempts) {
                exchange.getResponseHeaders().add(HttpHeaders.RETRY_AFTER, Integer.toString(behaviour.retryAfterSeconds));
                code = HttpStatus.SERVICE_UNAVAILABLE.value();
                response = error(attempt <= behaviour.busyAttempts ? GatewayApi.BUSY_ERROR : GatewayApi.STOPPING_ERROR);
            } else if (behaviour.failFirstAttempt && attempt == GatewayClient.FIRST_ATTEMPT) {
                code = HttpStatus.SERVICE_UNAVAILABLE.value();
                response = error("unavailable");
            } else if (behaviour.refuseWith != null) {
                code = behaviour.refuseWith;
                response = error("refused");
            } else if (behaviour.rawAnswer != null) {
                code = HttpStatus.OK.value();
                response = behaviour.rawAnswer.apply(request);
            } else if (waited && behaviour.rejectReason != null) {
                code = HttpStatus.OK.value();
                response = bankAnswer(request.transferId(), TransferStatus.REJECTED, behaviour.rejectReason, acceptedAt);
            } else if (waited) {
                code = HttpStatus.OK.value();
                response = bankAnswer(request.transferId(), TransferStatus.POSTED, null, acceptedAt);
            } else {
                code = HttpStatus.ACCEPTED.value();
                response = bankAnswer(request.transferId(), TransferStatus.ACCEPTED, null, acceptedAt);
            }
            if (HttpStatus.valueOf(code).is2xxSuccessful()) {
                accepted.putIfAbsent(request.transferId(), new Accepted(
                    new StatementLine(
                        request.transferId(), request.fromAccount(), request.toAccount(), request.amountCents(),
                        behaviour.rejectReason != null ? TransferStatus.REJECTED : TransferStatus.POSTED,
                        behaviour.rejectReason),
                    acceptedAt));
            }
            respond(exchange, code, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void answerTransfer(HttpExchange exchange) throws IOException {
        transferQuestions.incrementAndGet();
        var failure = options.transferFailure;
        var path = exchange.getRequestURI().getPath();
        var known = accepted.get(UUID.fromString(path.substring(path.lastIndexOf('/') + 1)));
        if (failure != null) {
            respond(exchange, failure, error("failed"));
        } else if (known == null) {
            respond(exchange, HttpStatus.NOT_FOUND.value(), "");
        } else {
            var line = known.line();
            respond(
                exchange,
                line.status().isFinal() ? HttpStatus.OK.value() : HttpStatus.ACCEPTED.value(),
                bankAnswer(line.transferId(), line.status(), line.rejectReason(), known.acceptedAt()));
        }
    }

    private void answerStatement(HttpExchange exchange) throws IOException {
        var behaviour = options;
        var path = exchange.getRequestURI().getPath();
        var periodStart = Instant.parse(path.substring(STATEMENT_PREFIX.length(), path.length() - STATEMENT_SUFFIX.length()));
        var query = exchange.getRequestURI().getRawQuery();
        var after = query == null ? null : Arrays.stream(query.split("&"))
            .filter(it -> it.startsWith(GatewayApi.AFTER_PARAM + "="))
            .map(it -> URLDecoder.decode(it.substring(it.indexOf('=') + 1), StandardCharsets.UTF_8))
            .findFirst().orElse(null);
        var requestNumber = statementRequests.incrementAndGet();
        var failure = behaviour.statementFailure.apply(requestNumber);
        if (failure != null) {
            respond(exchange, failure, error("failed"));
        } else if (!behaviour.periodClosed) {
            respond(exchange, HttpStatus.CONFLICT.value(), error("period-not-closed"));
        } else if (behaviour.rawStatement != null) {
            respond(exchange, HttpStatus.OK.value(), behaviour.rawStatement);
        } else {
            var page = behaviour.tamperStatement.apply(requestNumber, statement(behaviour, periodStart, after));
            respond(exchange, HttpStatus.OK.value(), objectMapper.writeValueAsString(page));
        }
    }

    /** In the order accepted, as the bank's; the cursor is the number of lines already served. */
    private Statement statement(Options behaviour, Instant periodStart, String after) {
        var periodEnd = periodStart.plus(behaviour.periodLength);
        var lines = accepted.values().stream()
            .filter(it -> !it.acceptedAt().isBefore(periodStart) && it.acceptedAt().isBefore(periodEnd))
            .sorted(Comparator.comparing(Accepted::acceptedAt).thenComparing(it -> it.line().transferId()))
            .map(Accepted::line)
            .toList();
        var from = after == null ? 0 : Integer.parseInt(after);
        var page = lines.subList(from, Math.min(lines.size(), from + behaviour.statementPage));
        var next = from + page.size() < lines.size() ? Integer.toString(from + page.size()) : null;
        return new Statement(lines.size(), page, next);
    }

    private static String error(String code) {
        return "{\"error\":\"" + code + "\"}";
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Shaped as gateway/openapi.json's TransferResponse, though the generator reads fewer fields. */
    static String bankAnswer(UUID transferId, TransferStatus status, RejectReason reason, Instant acceptedAt) {
        var answer = new HashMap<String, Object>();
        answer.put("transferId", transferId);
        answer.put("status", status);
        answer.put("rejectReason", reason);
        answer.put("acceptedAt", acceptedAt.toString());
        answer.put("resultedAt", status.isFinal() ? acceptedAt.toString() : null);
        return ANSWER_MAPPER.writeValueAsString(answer);
    }
}
