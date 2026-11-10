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
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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

    public GatewayClient(GeneratorProperties props, ObjectMapper objectMapper, MeterRegistry registry) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.unreadable = Counter.builder(UNREADABLE_METRIC)
            .description("2xx answers with a value this client does not know; the status code still decided the outcome, and a statement line is not compared")
            .register(registry);
        // Its default executor is an unbounded pool of platform threads, one per answer being handled: when the bank
        // answered slowly, hundreds of them outgrew the container's memory outside the heap.
        this.http = HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
        // A gateway one version ahead may send a status or reason this client does not know; the code still decides the outcome.
        this.responseReader = objectMapper.readerFor(TransferResponse.class)
            .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        this.statementReader = objectMapper.readerFor(Statement.class)
            .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
    }

    public Statement statement(UUID runId, @Nullable UUID after) {
        var query = "?" + GatewayApi.LIMIT_PARAM + "=" + GatewayApi.STATEMENT_MAX_PAGE
            + (after == null ? "" : "&" + GatewayApi.AFTER_PARAM + "=" + after);
        var request = HttpRequest.newBuilder(URI.create(props.gatewayUrl() + GatewayApi.statementPath(runId) + query))
            .timeout(props.requestTimeout())
            .GET()
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GatewayUnavailableException("No answer from the gateway to the statement of run " + runId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException("Interrupted while reading the statement of run " + runId, e);
        }
        var code = HttpStatusCode.valueOf(response.statusCode());
        if (code.is2xxSuccessful()) {
            var page = readStatement(response.body());
            if (page == null) {
                throw new BadGatewayAnswerException("Unreadable statement page of run " + runId);
            }
            return page;
        }
        if (code.isSameCodeAs(HttpStatus.CONFLICT)) {
            throw new PeriodNotClosedException();
        }
        if (code.is5xxServerError()) {
            throw new GatewayUnavailableException("The gateway answered " + code.value() + " to the statement of run " + runId);
        }
        throw new BadGatewayAnswerException("The gateway answered " + code.value() + " to the statement of run " + runId);
    }

    private @Nullable Statement readStatement(String body) {
        var tree = readTree(body);
        if (tree == null) {
            return null;
        }
        Statement page;
        try {
            page = statementReader.readValue(tree);
        } catch (Throwable e) {
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
            transfer.transferId(), transfer.runId(), transfer.fromAccount(), transfer.toAccount(), transfer.amountCents()));
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
            // Stays NO_RESPONSE if something unexpected is thrown: the request may have left by then.
            var sendResult = SendResult.NO_RESPONSE;
            HttpResponse<String> response = null;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
                sendResult = SendResult.RESPONSE;
            } catch (IOException e) {
                // Without a connection the request never left; any other I/O error may come after the gateway committed.
                if (e instanceof ConnectException || e instanceof HttpConnectTimeoutException) {
                    sendResult = SendResult.NOT_CONNECTED;
                } else {
                    mayHaveArrived = true;
                }
            } catch (InterruptedException e) {
                sendResult = SendResult.CANCELLED;
                throw e;
            } finally {
                sends.record(attempt, sendStarted, System.nanoTime(), sendResult, response == null ? null : response.statusCode());
            }
            var elapsed = Duration.ofNanos(System.nanoTime() - started);
            var code = response == null ? null : HttpStatusCode.valueOf(response.statusCode());
            if (response != null && code != null && !code.is5xxServerError()) {
                var body = read(response.body(), code.is2xxSuccessful());
                return new SubmissionResult(
                    body == null ? null : body.status(),
                    body == null ? null : body.rejectReason(),
                    attempt,
                    code.is2xxSuccessful() ? SubmissionOutcome.ACKNOWLEDGED : SubmissionOutcome.FAILED,
                    elapsed,
                    refusals);
            }
            var busy = response != null && code != null && code.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE) && isBusy(response.body());
            if (busy) {
                refusals++;
            } else if (response != null) {
                mayHaveArrived = true;
            }
            var givenUp = new SubmissionResult(
                null, null, attempt,
                mayHaveArrived ? SubmissionOutcome.UNKNOWN : SubmissionOutcome.FAILED,
                elapsed,
                refusals);
            // Checked before the backoff so a stop does not wait for it, and after it because the
            // stop may come while this request is sleeping.
            if (attempt >= props.maxAttempts() || !canRetry.getAsBoolean()) {
                return givenUp;
            }
            Thread.sleep(Math.max(backoff(attempt), busy ? retryAfterMillis(response) : 0));
            if (!canRetry.getAsBoolean()) {
                return givenUp;
            }
        }
    }

    private @Nullable TransferResponse read(String body, boolean acknowledged) {
        var tree = readTree(body);
        TransferResponse response = null;
        if (tree != null) {
            try {
                response = responseReader.readValue(tree);
            } catch (Throwable e) {
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
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean isBusy(String body) {
        try {
            return GatewayApi.BUSY_ERROR.equals(objectMapper.readValue(body, ApiError.class).error());
        } catch (Throwable e) {
            return false;
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
