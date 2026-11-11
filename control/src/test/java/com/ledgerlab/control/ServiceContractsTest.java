package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.control.gateway.ClosedPeriod;
import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.gateway.PeriodSummary;
import com.ledgerlab.control.generator.ClientReconciliation;
import com.ledgerlab.control.generator.ClientReconciliationReport;
import com.ledgerlab.control.generator.ClientReconciliationState;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.generator.LoadStatus;
import com.ledgerlab.control.generator.MinuteResponses;
import com.ledgerlab.control.generator.MinutesPage;
import com.ledgerlab.control.generator.MismatchedTransfer;
import com.ledgerlab.control.generator.Pulse;
import com.ledgerlab.control.generator.ReconciliationBreaks;
import com.ledgerlab.control.generator.StatementTotals;
import com.ledgerlab.control.generator.TransferIds;
import com.ledgerlab.control.generator.TransferSide;
import com.ledgerlab.control.generator.TransferSides;
import com.ledgerlab.control.generator.UnknownResolved;
import com.ledgerlab.control.ledger.AccountMismatch;
import com.ledgerlab.control.ledger.Books;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.ledger.LedgerCheck;
import com.ledgerlab.control.ledger.LedgerReconciliation;
import com.ledgerlab.control.ledger.PeriodCheck;
import com.ledgerlab.control.reporting.PeriodStats;
import com.ledgerlab.control.reporting.ReportingApi;
import java.io.File;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Control's models of the ledger, gateway, generator and reporting service against the specs those services serve (ADR 0011). */
class ServiceContractsTest {

    private static final String NULL_TYPE = "null";

    private static final class Spec {
        final JsonNode root;
        final String name;

        Spec(String property) {
            this.root = new ObjectMapper().readTree(new File(System.getProperty(property)));
            this.name = property;
        }

        JsonNode operation(HttpMethod method, String path) {
            var item = root.get("paths").get(path);
            var operation = item == null ? null : item.get(method.name().toLowerCase(Locale.ROOT));
            if (operation == null) {
                throw new IllegalStateException("no " + method + " " + path + " in " + name);
            }
            return operation;
        }

        JsonNode schemaOf(String schemaName) {
            var schema = root.get("components").get("schemas").get(schemaName);
            if (schema == null) {
                throw new IllegalStateException("no schema " + schemaName + " in " + name);
            }
            return schema;
        }
    }

    private record Call(Spec spec, HttpMethod method, String path) {
    }

    private record Answers(Spec spec, HttpMethod method, String path, List<String> codes) {
    }

    /** A model of control and the schema of the service it reads; the two services name their period reconciliation alike. */
    private record Model(Spec spec, Class<? extends Record> type, String schema) {
        Model(Spec spec, Class<? extends Record> type) {
            this(spec, type, type.getSimpleName());
        }

        JsonNode properties() {
            return spec.schemaOf(schema).get("properties");
        }
    }

    private final Spec ledger = new Spec("ledger.spec");
    private final Spec gateway = new Spec("gateway.spec");
    private final Spec generator = new Spec("generator.spec");
    private final Spec reporting = new Spec("reporting.spec");
    private final List<Model> answers = List.of(
        new Model(ledger, LedgerReconciliation.class, "PeriodReconciliation"),
        new Model(ledger, PeriodCheck.class),
        new Model(ledger, AccountMismatch.class),
        new Model(ledger, Books.class),
        new Model(gateway, PeriodSummary.class),
        new Model(gateway, ClosedPeriod.class),
        new Model(generator, ClientReconciliation.class, "PeriodReconciliation"),
        new Model(generator, ClientReconciliationReport.class),
        new Model(generator, StatementTotals.class),
        new Model(generator, ReconciliationBreaks.class),
        new Model(generator, TransferIds.class),
        new Model(generator, TransferSides.class),
        new Model(generator, MismatchedTransfer.class),
        new Model(generator, TransferSide.class),
        new Model(generator, UnknownResolved.class),
        new Model(generator, LoadStatus.class),
        new Model(generator, Pulse.class),
        new Model(generator, MinutesPage.class),
        new Model(generator, MinuteResponses.class),
        new Model(reporting, PeriodStats.class));

    private List<String> fieldsOf(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private List<String> names(JsonNode node) {
        return node.values().stream().map(JsonNode::asString).toList();
    }

    private List<String> pathParameters(String path) {
        return Pattern.compile("\\{(\\w+)}").matcher(path).results().map(it -> it.group(1)).toList();
    }

    @Test
    @DisplayName("every call control makes is in the spec of its service, with its parameters")
    void every_call_control_makes_is_in_the_spec_of_its_service_with_its_parameters() {
        var calls = List.of(
            new Call(ledger, HttpMethod.GET, LedgerApi.RECONCILIATION_PATH),
            new Call(ledger, HttpMethod.POST, LedgerApi.CHECK_PATH),
            new Call(ledger, HttpMethod.GET, LedgerApi.BOOKS_PATH),
            new Call(gateway, HttpMethod.GET, GatewayApi.SUMMARY_PATH),
            new Call(gateway, HttpMethod.POST, GatewayApi.CLOSE_PATH),
            new Call(generator, HttpMethod.GET, GeneratorApi.LOAD_PATH),
            new Call(generator, HttpMethod.GET, GeneratorApi.MINUTES_PATH),
            new Call(generator, HttpMethod.GET, GeneratorApi.RECONCILIATION_PATH),
            new Call(generator, HttpMethod.POST, GeneratorApi.RECONCILIATION_PATH),
            new Call(reporting, HttpMethod.GET, ReportingApi.STATS_PATH));
        calls.forEach(call -> assertThat(parametersOf(call)).as(call.method() + " " + call.path()).containsAll(pathParameters(call.path())));
        assertThat(parametersOf(new Call(generator, HttpMethod.GET, GeneratorApi.MINUTES_PATH))).contains(GeneratorApi.MINUTES_AFTER);
    }

    private List<String> parametersOf(Call call) {
        var declared = call.spec().operation(call.method(), call.path()).get("parameters");
        return declared == null ? List.of() : declared.values().stream().map(it -> it.get("name").asString()).toList();
    }

    @Test
    void every_field_control_reads_is_in_the_spec() {
        answers.forEach(model ->
            assertThat(model.properties().propertyNames()).as(model.type().getSimpleName()).containsAll(fieldsOf(model.type())));
    }

    @Test
    void every_answer_control_tells_apart_is_a_code_the_service_declares() {
        var declared = List.of(
            new Answers(ledger, HttpMethod.GET, LedgerApi.RECONCILIATION_PATH, List.of("200", "404")),
            new Answers(ledger, HttpMethod.POST, LedgerApi.CHECK_PATH, List.of("202")),
            new Answers(ledger, HttpMethod.GET, LedgerApi.BOOKS_PATH, List.of("200")),
            new Answers(gateway, HttpMethod.GET, GatewayApi.SUMMARY_PATH, List.of("200", "404")),
            new Answers(gateway, HttpMethod.POST, GatewayApi.CLOSE_PATH, List.of("200", "404")),
            new Answers(generator, HttpMethod.GET, GeneratorApi.LOAD_PATH, List.of("200")),
            new Answers(generator, HttpMethod.GET, GeneratorApi.MINUTES_PATH, List.of("200")),
            new Answers(generator, HttpMethod.GET, GeneratorApi.RECONCILIATION_PATH, List.of("200", "404")),
            new Answers(generator, HttpMethod.POST, GeneratorApi.RECONCILIATION_PATH, List.of("202", "404")),
            new Answers(reporting, HttpMethod.GET, ReportingApi.STATS_PATH, List.of("200")));
        declared.forEach(answer -> {
            var method = answer.method();
            var path = answer.path();
            assertThat(answer.spec().operation(method, path).get("responses").propertyNames()).as(method + " " + path).containsAll(answer.codes());
        });
    }

    @Test
    void a_field_control_reads_as_never_null_is_never_null_in_the_spec() {
        answers.forEach(model -> {
            var type = model.type();
            var properties = model.properties();
            Arrays.stream(type.getRecordComponents()).filter(it -> !it.getAnnotatedType().isAnnotationPresent(Nullable.class)).forEach(field -> {
                var property = properties.get(field.getName());
                var declaredTypes = property.get("type");
                var alternatives = new ArrayList<JsonNode>();
                Stream.of(property.get("oneOf"), property.get("anyOf")).filter(it -> it != null).forEach(it -> alternatives.addAll(it.values()));
                var nullable = (declaredTypes != null && declaredTypes.isArray() && names(declaredTypes).contains(NULL_TYPE))
                    || alternatives.stream().anyMatch(it -> it.get("type") != null && it.get("type").asString().equals(NULL_TYPE));
                assertThat(nullable).as(type.getSimpleName() + "." + field.getName()).isFalse();
            });
        });
    }

    @Test
    @DisplayName("the states of the clients' reconciliation and the ledger's checks are the ones the specs list")
    void the_states_of_the_clients_reconciliation_and_the_ledgers_checks_are_the_ones_the_specs_list() {
        var states = names(generator.schemaOf("PeriodReconciliation").get("properties").get("state").get("enum"));
        assertThat(Arrays.stream(ClientReconciliationState.values()).map(Enum::name).toList()).containsExactlyInAnyOrderElementsOf(states);

        var checks = names(ledger.schemaOf("PeriodCheck").get("properties").get("failedChecks").get("items").get("enum"));
        assertThat(Arrays.stream(LedgerCheck.values()).map(Enum::name).toList()).containsExactlyInAnyOrderElementsOf(checks);
    }

    @Test
    @DisplayName("an answer without a field control needs is unreadable, not read with nothing in the field")
    void an_answer_without_a_field_control_needs_is_unreadable_not_read_with_nothing_in_the_field() throws Exception {
        var mapper = new ObjectMapper();
        for (var model : answers) {
            var type = model.type();
            for (var component : type.getRecordComponents()) {
                if (component.getType().isPrimitive() || component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                    continue;
                }
                ObjectNode answer = mapper.valueToTree(sampleOf(type));
                answer.remove(component.getName());

                assertThatThrownBy(() -> mapper.treeToValue(answer, type))
                    .as(type.getSimpleName() + " without " + component.getName())
                    .isInstanceOf(JacksonException.class);
            }
        }
    }

    private static Object sampleOf(Class<?> type) throws Exception {
        if (type == UUID.class) {
            return UUID.randomUUID();
        }
        if (type == Instant.class) {
            return Instant.parse("2026-11-10T00:00:00Z");
        }
        if (type == String.class) {
            return "text";
        }
        if (type == List.class) {
            return List.of();
        }
        if (type == Map.class) {
            return Map.of();
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == Double.class) {
            return 0.0;
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        var components = type.getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (var i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = sampleOf(types[i]);
        }
        return type.getDeclaredConstructor(types).newInstance(values);
    }
}
