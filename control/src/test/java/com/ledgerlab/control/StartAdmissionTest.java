package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.ledgerlab.control.api.ControlErrors;
import com.ledgerlab.control.runs.RunRequest;
import com.ledgerlab.control.runs.RunService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@TestPropertySource(properties = {"control.runs.max-concurrent-starts=2", "control.runs.start-wait=50ms"})
class StartAdmissionTest extends IntegrationTest {

    @MockitoSpyBean RunService runService;

    @Test
    void a_start_over_the_bound_is_refused_with_503_busy_and_does_not_hold_up_the_reads() throws Exception {
        var held = new CountDownLatch(1);
        var inside = new AtomicInteger();
        doAnswer(call -> {
            inside.incrementAndGet();
            held.await();
            return call.callRealMethod();
        }).when(runService).start(any(UUID.class), any(RunRequest.class));
        var sessions = Stream.generate(() -> sessionOf(invite())).limit(3).toList();

        try (var pool = Executors.newFixedThreadPool(2)) {
            var holding = sessions.subList(0, 2).stream().map(session -> pool.submit(() -> start(session).getStatusCode().value())).toList();
            await().atMost(Duration.ofSeconds(10)).until(() -> inside.get() == 2);

            var refused = start(sessions.get(2));
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getBody().get("error")).isEqualTo(ControlErrors.BUSY);
            assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(get("/api/sessions/" + sessions.get(0) + "/runs").getStatusCode()).isEqualTo(HttpStatus.OK);

            held.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : holding) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsOnly(HttpStatus.ACCEPTED.value());
        }
        assertThat(start(sessions.get(2)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void a_start_that_fails_gives_its_permit_back() {
        var session = sessionOf(invite());
        for (var i = 0; i < 3; i++) {
            assertThat(startWithTps(session, 7).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        assertThat(start(session).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
