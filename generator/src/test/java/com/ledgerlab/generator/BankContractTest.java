package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.generator.bank.ApiError;
import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.Statement;
import com.ledgerlab.generator.bank.StatementLine;
import com.ledgerlab.generator.bank.SubmitTransferRequest;
import com.ledgerlab.generator.bank.TransferResponse;
import com.ledgerlab.generator.bank.TransferStatus;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The generator's model of the bank (package bank) against the bank's published spec, gateway/openapi.json (ADR 0011). */
class BankContractTest {

    private static final String ENTRY_COUNT_FIELD = "entryCount";

    private final JsonNode spec = new ObjectMapper().readTree(new File(System.getProperty("bank.spec")));
    private final JsonNode schemas = spec.get("components").get("schemas");

    private JsonNode schemaOf(Class<? extends Record> type) {
        return Objects.requireNonNull(schemas.get(type.getSimpleName()), "no schema " + type.getSimpleName() + " in the spec");
    }

    private static List<String> fieldsOf(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(component -> component.getName()).toList();
    }

    private static List<String> names(JsonNode array) {
        return array.values().stream().map(JsonNode::asString).toList();
    }

    private static <E extends Enum<E>> List<String> namesOf(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    private JsonNode operation(String path, String method) {
        var item = spec.get("paths").get(path);
        return Objects.requireNonNull(item == null ? null : item.get(method), "no " + method + " " + path + " in the spec");
    }

    @Test
    void the_path_and_query_parameter_the_generator_calls_are_in_the_spec() {
        var submit = operation(GatewayApi.TRANSFERS_PATH, "post");

        assertThat(submit.get("parameters").values()).extracting(parameter -> parameter.get("name").asString()).contains(GatewayApi.WAIT_PARAM);
    }

    @Test
    @DisplayName("the spec names the busy refusal of a submission, which the generator counts as not taken")
    void the_spec_names_the_busy_refusal_of_a_submission_which_the_generator_counts_as_not_taken() {
        var busy = operation(GatewayApi.TRANSFERS_PATH, "post").get("responses")
            .get(Integer.toString(HttpStatus.SERVICE_UNAVAILABLE.value()));

        assertThat(busy).as("503 of POST " + GatewayApi.TRANSFERS_PATH).isNotNull();
        assertThat(busy.get("description").asString()).contains("\"" + GatewayApi.BUSY_ERROR + "\"");
    }

    @Test
    @DisplayName("the statement path, its paging parameters and the not-closed answer are in the spec")
    void the_statement_path_its_paging_parameters_and_the_not_closed_answer_are_in_the_spec() {
        var statement = operation(GatewayApi.STATEMENT_PATH, "get");

        assertThat(statement.get("parameters").values()).extracting(parameter -> parameter.get("name").asString())
            .contains(GatewayApi.AFTER_PARAM, GatewayApi.LIMIT_PARAM);
        assertThat(statement.get("responses").propertyNames()).contains(Integer.toString(HttpStatus.CONFLICT.value()));
    }

    @Test
    void every_field_the_generator_sends_or_reads_is_in_the_spec() {
        for (var type : List.of(SubmitTransferRequest.class, TransferResponse.class, Statement.class, StatementLine.class, ApiError.class)) {
            assertThat(schemaOf(type).get("properties").propertyNames()).as(type.getSimpleName()).containsAll(fieldsOf(type));
        }
    }

    @Test
    void the_fields_the_generator_looks_up_by_name_are_fields_of_its_model() {
        assertThat(fieldsOf(Statement.class)).contains(Statement.ITEMS_FIELD, ENTRY_COUNT_FIELD);
        assertThat(fieldsOf(StatementLine.class)).contains(StatementLine.REJECT_REASON_FIELD);
        assertThat(fieldsOf(TransferResponse.class)).contains(TransferResponse.REJECT_REASON_FIELD);
    }

    @Test
    void the_generator_sends_every_field_the_spec_requires() {
        assertThat(fieldsOf(SubmitTransferRequest.class)).containsAll(names(schemaOf(SubmitTransferRequest.class).get("required")));
    }

    @Test
    @DisplayName("the spec promises the statement's entry count on every page, since completeness rests on it")
    void the_spec_promises_the_statements_entry_count_on_every_page_since_completeness_rests_on_it() {
        assertThat(names(schemaOf(Statement.class).get("required"))).contains(ENTRY_COUNT_FIELD);
    }

    @Test
    @DisplayName("statuses and reject reasons are the ones the spec lists, in answers and in the statement")
    void statuses_and_reject_reasons_are_the_ones_the_spec_lists_in_answers_and_in_the_statement() {
        for (var type : List.of(TransferResponse.class, StatementLine.class)) {
            var properties = schemaOf(type).get("properties");
            assertThat(namesOf(TransferStatus.class)).as(type.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(names(properties.get("status").get("enum")));
            assertThat(namesOf(RejectReason.class)).as(type.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(names(properties.get("rejectReason").get("enum")));
        }
    }
}
