package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.gateway.api.GatewayErrors;
import com.ledgerlab.gateway.domain.Period;
import com.ledgerlab.gateway.domain.StatementCursor;
import com.ledgerlab.gateway.domain.TransferStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class StatementTest extends IntegrationTest {

    private ResponseEntity<Map<String, Object>> statement(Period period) {
        return statement(period, "");
    }

    private ResponseEntity<Map<String, Object>> statement(Period period, String query) {
        return get("/v2/periods/" + period.start() + "/statement" + query);
    }

    private List<Map<String, Object>> allPages(Period period, int limit) {
        var pages = new ArrayList<Map<String, Object>>();
        Object after = null;
        do {
            var page = statement(period, "?limit=" + limit + (after == null ? "" : "&after=" + after)).getBody();
            pages.add(page);
            after = page.get("next");
        } while (after != null);
        return pages;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<String> idsOf(List<Map<String, Object>> pages) {
        return pages.stream().flatMap(page -> itemsOf(page).stream()).map(line -> (String) line.get("transferId")).toList();
    }

    @Test
    @DisplayName("the statement of a closed period lists every transfer in acceptance order, page by page, whatever its status")
    void the_statement_of_a_closed_period_lists_every_transfer_in_acceptance_order_page_by_page_whatever_its_status() {
        var period = lastEndedPeriod();
        var sameInstant = period.start().plusSeconds(2);
        var sent = new ArrayList<UUID>();
        sent.add(stored(period.start(), TransferStatus.POSTED, null, period.start().plusSeconds(1)));
        sent.add(stored(period.start().plusSeconds(1), TransferStatus.REJECTED, RejectReason.INSUFFICIENT_FUNDS, period.start().plusSeconds(2)));
        // Two accepted in the same microsecond: the id orders them, as the database compares it.
        var twins = new ArrayList<>(List.of(acceptedAt(sameInstant), acceptedAt(sameInstant)));
        twins.sort(Comparator.comparing(UUID::toString));
        sent.addAll(twins);
        sent.add(acceptedAt(period.end().minus(1, ChronoUnit.MICROS)));

        var closed = close(period);
        var pages = allPages(period, 2);

        assertThat(pages).extracting(page -> itemsOf(page).size()).containsExactly(2, 2, 1);
        assertThat(idsOf(pages)).containsExactlyElementsOf(sent.stream().map(UUID::toString).toList());
        assertThat(pages).allSatisfy(page -> assertThat(page)
            .containsEntry("periodStart", period.start().toString())
            .containsEntry("closedAt", closed.getBody().get("closedAt"))
            .containsEntry("entryCount", 5));
        assertThat(itemsOf(pages.getFirst()).get(1))
            .containsEntry("fromAccount", 1).containsEntry("toAccount", 2).containsEntry("amountCents", 100)
            .containsEntry("status", TransferStatus.REJECTED.name()).containsEntry("rejectReason", RejectReason.INSUFFICIENT_FUNDS.name())
            .containsKeys("acceptedAt", "resultedAt").doesNotContainKey("publishedAt");
    }

    @Test
    @DisplayName("a transfer accepted just before the boundary is in that period's statement although its result came after, and one accepted at the boundary is in the next")
    void a_transfer_accepted_just_before_the_boundary_is_in_that_periods_statement_and_one_accepted_at_the_boundary_is_in_the_next() {
        var next = lastEndedPeriod();
        var period = next.previous();
        var lastOfPeriod = stored(next.start().minus(1, ChronoUnit.MICROS), TransferStatus.POSTED, null, next.start().plusSeconds(5));
        var firstOfNext = stored(next.start(), TransferStatus.POSTED, null, next.start().plusSeconds(5));
        close(period);
        close(next);

        assertThat(idsOf(allPages(period, 100))).containsExactly(lastOfPeriod.toString());
        assertThat(idsOf(allPages(next, 100))).containsExactly(firstOfNext.toString());
        assertThat(summary(period).getBody()).containsEntry("accepted", 1).containsEntry("posted", 1);
    }

    @Test
    void there_is_no_statement_before_the_period_is_closed() {
        var ended = lastEndedPeriod();
        acceptedAt(ended.start());
        var current = ended.next();

        for (var open : List.of(ended, current)) {
            var refused = statement(open);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("error")).isEqualTo(GatewayErrors.PERIOD_NOT_CLOSED);
        }
    }

    @Test
    void a_period_that_has_not_begun_is_off_the_grid_or_is_older_than_the_retention_has_no_statement_at_all() {
        var current = lastEndedPeriod().next();
        var offGrid = new Period(current.start().minusSeconds(1), current.end().minusSeconds(1));
        var tooOld = Period.containing(Instant.now().minus(props.periods().retention()), props.periods().length()).previous();

        for (var unknown : List.of(current.next(), offGrid, tooOld)) {
            var refused = statement(unknown);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(refused.getBody().get("error")).isEqualTo(GatewayErrors.UNKNOWN_PERIOD);
        }
        assertThat(get("/v2/periods/yesterday/statement").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void an_invalid_limit_or_a_cursor_that_is_not_of_the_period_is_a_bad_request() {
        var period = lastEndedPeriod();
        var inPeriod = acceptedAt(period.start());
        close(period);
        var ofAnotherPeriod = new StatementCursor(period.end(), UUID.randomUUID()).text();

        assertThat(statement(period, "?limit=0").getBody().get("error")).isEqualTo(GatewayErrors.INVALID_LIMIT);
        assertThat(statement(period, "?limit=1001").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statement(period, "?limit=1000").getStatusCode()).isEqualTo(HttpStatus.OK);
        for (var cursor : List.of(ofAnotherPeriod, inPeriod.toString(), "garbage")) {
            var refused = statement(period, "?after=" + cursor);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().get("error")).isEqualTo(GatewayErrors.INVALID_CURSOR);
        }
    }
}
