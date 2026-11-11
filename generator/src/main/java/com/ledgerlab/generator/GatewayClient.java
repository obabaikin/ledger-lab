package com.ledgerlab.generator;

import com.ledgerlab.generator.bank.ApiError;
import com.ledgerlab.generator.bank.Statement;
import com.ledgerlab.generator.bank.StatementLine;
import com.ledgerlab.generator.bank.SubmitTransferRequest;
import com.ledgerlab.generator.bank.TransferResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.EnumFeature;

/** Retries reuse the transfer id, the idempotency key, so a retry after a timeout cannot create a second transfer. */
@Component
public class GatewayClient {

    public static final int FIRST_ATTEMPT = 1;
    public static final String UNREADABLE_METRIC = "generator.gateway.unreadable";
    private static final int LOGGED_IDS = 10;

    private static final double JITTER_MIN = 0.5;
    private static final double JITTER_MAX = 1.5;

    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);

    private final GeneratorProperties props;
    private final ObjectMapper objectMapper;
    private final Counter unreadable;
    private final HttpClient http;
    private final ObjectReader responseReader;
    private final ObjectReader statementReader;

    @Autowired
    public GatewayClient(GeneratorProperties props, ObjectMapper objectMapper, MeterRegistry registry) {
        // Its default executor is an unbounded pool of platform threads, one per answer being handled: when the bank
        // answered slowly, hundreds of them outgrew the container's memory outside the heap.
        this(props, objectMapper, registry, HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build());
    }

    GatewayClient(GeneratorProperties props, ObjectMapper objectMapper, MeterRegistry registry, HttpClient http) {
        this.props = props;
        this.http = http;
        this.objectMapper = objectMapper;
        this.unreadable = Counter.builder(UNREADABLE_METRIC)
            .description("2xx answers with a value this client does not know; the status code still decided the outcome, and a statement line is not compared")
            .register(registry);
        // A gateway one version ahead may send a status or reason this client does not know; the code still decides the outcome.
        this.responseReader = objectMapper.readerFor(TransferResponse.class)
            .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        this.statementReader = objectMapper.readerFor(Statement.class)
            .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
    }

    public Statement statement(Instant periodStart, @Nullable String after) {
        var query = "?" + GatewayApi.LIMIT_PARAM + "=" + GatewayApi.STATEMENT_MAX_PAGE
            + (after == null ? "" : "&" + GatewayApi.AFTER_PARAM + "=" + URLEncoder.encode(after, StandardCharsets.UTF_8));
        var what = "the statement of the period from " + periodStart;
        var response = get(GatewayApi.statementPath(periodStart) + query, what);
        var code = HttpStatusCode.valueOf(response.statusCode());
        if (code.is2xxSuccessful()) {
            var page = readStatement(response.body());
            if (page == null) {
                throw new BadGatewayAnswerException("Unreadable page of " + what);
            }
            return page;
        }
        if (code.isSameCodeAs(HttpStatus.CONFLICT)) {
            throw new PeriodNotClosedException("The bank has not closed the period from " + periodStart);
        }
        throw unexpected(code, what);
    }

    /** The transfer as the bank has it now, or null if the bank does not have it. */
    public @Nullable TransferResponse transfer(UUID transferId) {
        var what = "transfer " + transferId;
        var response = get(GatewayApi.transferPath(transferId), what);
        var code = HttpStatusCode.valueOf(response.statusCode());
        if (code.is2xxSuccessful()) {
            var transfer = read(response.body(), true);
            if (transfer == null) {
                throw new BadGatewayAnswerException("Unreadable answer about " + what);
            }
            return transfer;
        }
        if (code.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return null;
        }
        throw unexpected(code, what);
    }

    private HttpResponse<String> get(String pathAndQuery, String what) {
        var request = HttpRequest.newBuilder(URI.create(props.gatewayUrl() + pathAndQuery))
            .timeout(props.requestTimeout())
            .GET()
            .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GatewayUnavailableException("No answer from the gateway about " + what, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException("Interrupted while asking the gateway about " + what, e);
        }
    }

    private static RuntimeException unexpected(HttpStatusCode code, String what) {
        var message = "The gateway answered " + code.value() + " about " + what;
        return code.is5xxServerError() ? new GatewayUnavailableException(message) : new BadGatewayAnswerException(message);
    }

    private @Nullable Statement readStatement(String body) {
        var tree = readTree(body);
        if (tree == null) {
            return null;
        }
        Statement page;
        try {
            page = statementReader.readValue(tree);
        } catch (JacksonException e) {
            return null;
        }
        if (page == null) {
            return null;
        }
        var lines = tree.get(Statement.ITEMS_FIELD).values().iterator();
        var lost = new ArrayList<UUID>();
        for (var line : page.items()) {
            if (!lines.hasNext()) {
                break;
            }
            var node = lines.next();
            if (line.status() == null || (line.rejectReason() == null && hasValue(node, StatementLine.REJECT_REASON_FIELD))) {
                lost.add(line.transferId());
            }
        }
        if (!lost.isEmpty()) {
            unreadable.increment();
            log.warn("Statement page with {} lines this client cannot read in full, the first: {}", lost.size(), lost.subList(0, Math.min(lost.size(), LOGGED_IDS)));
        }
        return page;
    }

    public SubmissionResult submit(PlannedTransfer transfer, @Nullable Duration wait) throws InterruptedException {
        return submit(transfer, wait, () -> true, SendRecorder.NONE);
    }

    public SubmissionResult submit(PlannedTransfer transfer, @Nullable Duration wait, BooleanSupplier canRetry, SendRecorder sends)
        throws InterruptedException {
        var url = props.gatewayUrl() + GatewayApi.TRANSFERS_PATH + (wait == null ? "" : "?" + GatewayApi.WAIT_PARAM + "=" + wait);
        var payload = objectMapper.writeValueAsString(new SubmitTransferRequest(
            transfer.transferId(), transfer.fromAccount(), transfer.toAccount(), transfer.amountCents()));
        var request = HttpRequest.newBuilder(URI.create(url))
            .timeout((wait == null ? Duration.ZERO : wait).plus(props.requestTimeout()))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        var started = System.nanoTime();
        var attempt = 0;
        var mayHaveArrived = false;
        var refusals = 0;
        while (true) {
            attempt++;
            var sendStarted = System.nanoTime();
            HttpResponse<String> response = null;
            ResponseKind kind;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
                kind = kindOf(response);
            } catch (ConnectException | HttpConnectTimeoutException e) {
                kind = ResponseKind.NOT_CONNECTED;
            } catch (IOException e) {
                kind = isSendError(e) ? ResponseKind.SEND_ERROR : ResponseKind.NO_RESPONSE;
                if (kind == ResponseKind.SEND_ERROR) {
                    log.warn("Transfer {} could not be sent", transfer.transferId(), e);
                }
            } catch (InterruptedException e) {
                sends.record(attempt, sendStarted, System.nanoTime(), ResponseKind.CANCELLED, false);
                throw e;
            }
            sends.record(attempt, sendStarted, System.nanoTime(), kind, response != null && response.statusCode() == HttpStatus.OK.value());
            var elapsed = Duration.ofNanos(System.nanoTime() - started);
            if (response != null && (kind == ResponseKind.ACCEPTED || kind == ResponseKind.INVALID)) {
                var acknowledged = kind == ResponseKind.ACCEPTED;
                var body = read(response.body(), acknowledged);
                return new SubmissionResult(
                    body == null ? null : body.status(),
                    body == null ? null : body.rejectReason(),
                    body == null ? null : body.acceptedAt(),
                    attempt,
                    acknowledged ? SubmissionOutcome.ACKNOWLEDGED : SubmissionOutcome.FAILED,
                    elapsed,
                    refusals);
            }
            var notTaken = kind == ResponseKind.BUSY || kind == ResponseKind.STOPPING;
            if (kind == ResponseKind.BUSY) {
                refusals++;
            } else if (!notTaken && kind != ResponseKind.NOT_CONNECTED) {
                mayHaveArrived = true;
            }
            var givenUp = new SubmissionResult(
                null, null, null, attempt,
                mayHaveArrived ? SubmissionOutcome.UNKNOWN : SubmissionOutcome.FAILED,
                elapsed,
                refusals);
            // Checked before the backoff so a stop does not wait for it, and after it because the
            // stop may come while this request is sleeping.
            if (kind == ResponseKind.SEND_ERROR || attempt >= props.maxAttempts() || !canRetry.getAsBoolean()) {
                return givenUp;
            }
            Thread.sleep(Math.max(backoff(attempt), notTaken ? retryAfterMillis(response) : 0));
            if (!canRetry.getAsBoolean()) {
                return givenUp;
            }
        }
    }

    private ResponseKind kindOf(HttpResponse<String> response) {
        var code = HttpStatusCode.valueOf(response.statusCode());
        if (code.is2xxSuccessful()) {
            return ResponseKind.ACCEPTED;
        }
        if (!code.is5xxServerError()) {
            return ResponseKind.INVALID;
        }
        if (code.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) {
            var error = errorOf(response.body());
            if (GatewayApi.BUSY_ERROR.equals(error)) {
                return ResponseKind.BUSY;
            }
            if (GatewayApi.STOPPING_ERROR.equals(error)) {
                return ResponseKind.STOPPING;
            }
        }
        return ResponseKind.SERVER_ERROR;
    }

    /**
     * The HTTP client hands on anything thrown while it sends as an IOException around it. An I/O failure may pass on a
     * retry; an error in the client itself will not, and the request may have left before it.
     */
    private static boolean isSendError(IOException e) {
        var cause = e.getCause();
        return cause != null && !(cause instanceof IOException);
    }

    private @Nullable TransferResponse read(String body, boolean acknowledged) {
        var tree = readTree(body);
        TransferResponse response = null;
        if (tree != null) {
            try {
                response = responseReader.readValue(tree);
            } catch (JacksonException e) {
                response = null;
            }
        }
        var reasonLost = response != null && tree != null && response.rejectReason() == null && hasValue(tree, TransferResponse.REJECT_REASON_FIELD);
        if (acknowledged && (response == null || reasonLost)) {
            unreadable.increment();
            log.warn("Gateway answer with a value this client does not know: {}", body);
        }
        return response;
    }

    private @Nullable JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private @Nullable String errorOf(String body) {
        try {
            var error = objectMapper.readValue(body, ApiError.class);
            return error == null ? null : error.error();
        } catch (JacksonException e) {
            return null;
        }
    }

    private static long retryAfterMillis(@Nullable HttpResponse<String> response) {
        if (response == null) {
            return 0;
        }
        var seconds = response.headers().firstValue(HttpHeaders.RETRY_AFTER).orElse(null);
        if (seconds == null) {
            return 0;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(seconds)).toMillis();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasValue(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && !value.isNull();
    }

    /** Jitter spreads the retries of many clients that failed together, so a recovering gateway is not hit at once. */
    private long backoff(int attempt) {
        var base = props.retryBackoff().toMillis() << (attempt - FIRST_ATTEMPT);
        return (long) (base * ThreadLocalRandom.current().nextDouble(JITTER_MIN, JITTER_MAX));
    }
}
