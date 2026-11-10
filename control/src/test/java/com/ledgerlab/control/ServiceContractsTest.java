package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.control.gateway.ClosedPeriod;
import com.ledgerlab.control.gateway.GatewayApi;
import com.ledgerlab.control.gateway.RunSummary;
import com.ledgerlab.control.generator.ClientReconciliationReport;
import com.ledgerlab.control.generator.GeneratorApi;
import com.ledgerlab.control.generator.JournalTotals;
import com.ledgerlab.control.generator.ReconciliationBreaks;
import com.ledgerlab.control.generator.SimulationState;
import com.ledgerlab.control.generator.SimulationStatus;
import com.ledgerlab.control.generator.StartSimulationRequest;
import com.ledgerlab.control.generator.TransferIds;
import com.ledgerlab.control.generator.UnknownResolved;
import com.ledgerlab.control.ledger.LedgerApi;
import com.ledgerlab.control.ledger.OpenAccountsRequest;
import com.ledgerlab.control.ledger.ReconciliationCheck;
import com.ledgerlab.control.ledger.ReconciliationReport;
import com.ledgerlab.control.reporting.BankLatency;
import com.ledgerlab.control.reporting.ReportingApi;
import com.ledgerlab.control.reporting.RunStats;
import com.ledgerlab.control.reporting.SecondStats;
import com.ledgerlab.control.reporting.StatsTotals;
import java.io.File;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

/** Control's models of the ledger, gateway and generator against the specs those services serve (ADR 0011). */
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

        JsonNode schemaOf(Class<?> type) {
            var schema = root.get("components").get("schemas").get(type.getSimpleName());
            if (schema == null) {
                throw new IllegalStateException("no schema " + type.getSimpleName() + " in " + name);
            }
            return schema;
        }
    }

    private record Call(Spec spec, HttpMethod method, String path) {
    }

    private record Answers(Spec spec, HttpMethod method, String path, List<String> codes) {
    }

    private record Model(Spec spec, Class<? extends Record> type) {
    }

    private final Spec ledger = new Spec("ledger.spec");
    private final Spec gateway = new Spec("gateway.spec");
    private final Spec generator = new Spec("generator.spec");
    private final Spec reporting = new Spec("reporting.spec");
    private final List<Class<? extends Record>> reportingModels = List.of(RunStats.class, StatsTotals.class, BankLatency.class, SecondStats.class);

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
    @DisplayName("every call control makes is in the spec of its service, with its path parameters")
    void every_call_control_makes_is_in_the_spec_of_its_service_with_its_path_parameters() {
        var calls = List.of(
            new Call(ledger, HttpMethod.POST, LedgerApi.ACCOUNTS_PATH),
            new Call(ledger, HttpMethod.GET, LedgerApi.RECONCILIATION_PATH),
            new Call(ledger, HttpMethod.DELETE, LedgerApi.RUN_PATH),
            new Call(gateway, HttpMethod.GET, GatewayApi.SUMMARY_PATH),
            new Call(gateway, HttpMethod.POST, GatewayApi.CLOSE_PATH),
            new Call(gateway, HttpMethod.DELETE, GatewayApi.RUN_PATH),
            new Call(generator, HttpMethod.POST, GeneratorApi.SIMULATIONS_PATH),
            new Call(generator, HttpMethod.GET, GeneratorApi.SIMULATION_PATH),
            new Call(generator, HttpMethod.POST, GeneratorApi.STOP_PATH),
            new Call(generator, HttpMethod.GET, GeneratorApi.RECONCILIATION_PATH),
            new Call(generator, HttpMethod.DELETE, GeneratorApi.JOURNAL_PATH),
            new Call(reporting, HttpMethod.GET, ReportingApi.STATS_PATH),
            new Call(reporting, HttpMethod.DELETE, ReportingApi.RUN_PATH));
        calls.forEach(call -> {
            var declared = call.spec().operation(call.method(), call.path()).get("parameters");
            List<String> parameters = declared == null ? List.of() : declared.values().stream().map(it -> it.get("name").asString()).toList();
            assertThat(parameters).as(call.method() + " " + call.path()).containsAll(pathParameters(call.path()));
        });
    }

    @Test
    void every_field_control_sends_or_reads_is_in_the_spec() {
        var models = new LinkedHashMap<Spec, List<Class<? extends Record>>>();
        models.put(ledger, List.of(OpenAccountsRequest.class, ReconciliationReport.class));
        models.put(gateway, List.of(RunSummary.class, ClosedPeriod.class));
        models.put(generator, List.of(
            StartSimulationRequest.class, SimulationStatus.class, ClientReconciliationReport.class, JournalTotals.class,
            ReconciliationBreaks.class, TransferIds.class, UnknownResolved.class));
        models.put(reporting, reportingModels);
        models.forEach((spec, types) ->
            types.forEach(type ->
                assertThat(spec.schemaOf(type).get("properties").propertyNames()).as(type.getSimpleName()).containsAll(fieldsOf(type))));
    }

    @Test
    void every_answer_control_tells_apart_is_a_code_the_service_declares() {
        var answers = List.of(
            new Answers(ledger, HttpMethod.POST, LedgerApi.ACCOUNTS_PATH, List.of("201", "400", "409")),
            new Answers(ledger, HttpMethod.GET, LedgerApi.RECONCILIATION_PATH, List.of("200")),
            new Answers(ledger, HttpMethod.DELETE, LedgerApi.RUN_PATH, List.of("204", "404")),
            new Answers(gateway, HttpMethod.GET, GatewayApi.SUMMARY_PATH, List.of("200")),
            new Answers(gateway, HttpMethod.POST, GatewayApi.CLOSE_PATH, List.of("200")),
            new Answers(gateway, HttpMethod.DELETE, GatewayApi.RUN_PATH, List.of("204", "404")),
            new Answers(generator, HttpMethod.POST, GeneratorApi.SIMULATIONS_PATH, List.of("201", "400", "409")),
            new Answers(generator, HttpMethod.GET, GeneratorApi.SIMULATION_PATH, List.of("200", "404")),
            new Answers(generator, HttpMethod.POST, GeneratorApi.STOP_PATH, List.of("200", "202", "404")),
            new Answers(generator, HttpMethod.GET, GeneratorApi.RECONCILIATION_PATH, List.of("200", "404", "409", "502", "503")),
            new Answers(generator, HttpMethod.DELETE, GeneratorApi.JOURNAL_PATH, List.of("204", "404")),
            new Answers(reporting, HttpMethod.GET, ReportingApi.STATS_PATH, List.of("200")),
            new Answers(reporting, HttpMethod.DELETE, ReportingApi.RUN_PATH, List.of("204", "404")));
        answers.forEach(answer -> {
            var method = answer.method();
            var path = answer.path();
            assertThat(answer.spec().operation(method, path).get("responses").propertyNames()).as(method + " " + path).containsAll(answer.codes());
        });
    }

    @Test
    @DisplayName("the generator names the error of clients still stopping, which control waits for")
    void the_generator_names_the_error_of_clients_still_stopping_which_control_waits_for() {
        var conflict = generator.operation(HttpMethod.GET, GeneratorApi.RECONCILIATION_PATH).get("responses").get("409");

        assertThat(conflict.get("description").asString()).contains("\"" + GeneratorApi.RUN_STILL_ACTIVE + "\"");
    }

    @Test
    void a_field_control_reads_as_never_null_is_never_null_in_the_spec() {
        var models = new LinkedHashMap<Spec, List<Class<? extends Record>>>();
        models.put(ledger, List.of(ReconciliationReport.class));
        models.put(gateway, List.of(RunSummary.class, ClosedPeriod.class));
        models.put(generator, List.of(
            SimulationStatus.class, ClientReconciliationReport.class, JournalTotals.class,
            ReconciliationBreaks.class, TransferIds.class, UnknownResolved.class));
        models.put(reporting, reportingModels);
        models.forEach((spec, types) ->
            types.forEach(type -> {
                var properties = spec.schemaOf(type).get("properties");
                Arrays.stream(type.getRecordComponents()).filter(it -> !it.getAnnotatedType().isAnnotationPresent(Nullable.class)).forEach(field -> {
                    var property = properties.get(field.getName());
                    var declaredTypes = property.get("type");
                    var alternatives = new ArrayList<JsonNode>();
                    Stream.of(property.get("oneOf"), property.get("anyOf")).filter(it -> it != null).forEach(it -> alternatives.addAll(it.values()));
                    var nullable = (declaredTypes != null && declaredTypes.isArray() && names(declaredTypes).contains(NULL_TYPE))
                        || alternatives.stream().anyMatch(it -> it.get("type") != null && it.get("type").asString().equals(NULL_TYPE));
                    assertThat(nullable).as(type.getSimpleName() + "." + field.getName()).isFalse();
                });
            }));
    }

    @Test
    void control_sends_every_field_the_spec_requires() {
        List.of(new Model(ledger, OpenAccountsRequest.class), new Model(generator, StartSimulationRequest.class)).forEach(model -> {
            var declared = model.spec().schemaOf(model.type()).get("required");
            List<String> required = declared == null ? List.of() : names(declared);
            assertThat(fieldsOf(model.type())).as(model.type().getSimpleName()).containsAll(required);
        });
    }

    @Test
    void simulation_states_and_ledger_checks_are_the_ones_the_specs_list() {
        var states = names(generator.schemaOf(SimulationStatus.class).get("properties").get("state").get("enum"));
        assertThat(Arrays.stream(SimulationState.values()).map(Enum::name).toList()).containsExactlyInAnyOrderElementsOf(states);

        var checks = names(ledger.schemaOf(ReconciliationReport.class).get("properties").get("failedChecks").get("items").get("enum"));
        assertThat(Arrays.stream(ReconciliationCheck.values()).map(Enum::name).toList()).containsExactlyInAnyOrderElementsOf(checks);
    }

    private static final List<Class<? extends Record>> ANSWERS = List.of(
        RunSummary.class, ClosedPeriod.class, ReconciliationReport.class, RunStats.class, StatsTotals.class, SecondStats.class,
        SimulationStatus.class, ClientReconciliationReport.class, ReconciliationBreaks.class, UnknownResolved.class, TransferIds.class);
    private static final List<String> READ_WITH_A_DEFAULT = List.of("requestedPerSecond");

    @Test
    @DisplayName("an answer without a field control needs is unreadable, not read with nothing in the field")
    void an_answer_without_a_field_control_needs_is_unreadable_not_read_with_nothing_in_the_field() throws Exception {
        var mapper = new ObjectMapper();
        for (var type : ANSWERS) {
            for (var component : type.getRecordComponents()) {
                if (component.getType().isPrimitive() || component.getAnnotatedType().isAnnotationPresent(Nullable.class)
                    || READ_WITH_A_DEFAULT.contains(component.getName())) {
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
