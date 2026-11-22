package com.ledgerlab.generator.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.generator.GeneratorProperties;
import com.ledgerlab.generator.IntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class SenderLeaseTest extends IntegrationTest {

    private static final Duration SAMPLE = Duration.ofMillis(10);

    @Autowired DataSource dataSource;

    private final List<SenderLease> leases = new ArrayList<>();
    private final CountDownLatch thaw = new CountDownLatch(1);

    @BeforeEach
    void noLease() {
        jdbcTemplate.update("DELETE FROM sender_lease");
    }

    @AfterEach
    void stopTheLeases() {
        thaw.countDown();
        leases.forEach(SenderLease::stop);
    }

    private SenderLease lease(String owner, DataSource source) {
        var p = props;
        var l = p.load();
        var q = p.lease();
        var settings = new GeneratorProperties(p.gatewayUrl(), p.connectTimeout(), p.requestTimeout(), p.maxAttempts(), p.retryBackoff(),
            p.maxInFlight(),
            new GeneratorProperties.Load(true, l.tps(), l.maxPulseTps(), l.maxPulse(), l.waitShare(), l.waitTimeout(), l.minutesRetention(), l.profile()),
            p.accounts(), p.journal(), p.periods(), p.reconciliation(), p.totals(),
            new GeneratorProperties.Lease(owner, q.duration(), q.renewEvery(), q.acquireEvery(), q.sendWindow()),
            p.minutes(), p.profile());
        var lease = new SenderLease(JdbcClient.create(source), settings, new SimpleMeterRegistry());
        leases.add(lease);
        return lease;
    }

    private SenderLease lease(String owner) {
        return lease(owner, dataSource);
    }

    /** Watches both for a while: at no sample may they both send. */
    private static void neverBoth(SenderLease a, SenderLease b, Duration time) throws InterruptedException {
        var deadline = System.nanoTime() + time.toNanos();
        while (System.nanoTime() - deadline < 0) {
            assertThat(a.granted() && b.granted()).as("both instances may send").isFalse();
            Thread.sleep(SAMPLE);
        }
    }

    @Test
    void of_two_instances_on_one_database_only_one_may_send() throws Exception {
        var a = lease("a");
        var b = lease("b");

        a.start();
        b.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> a.granted() || b.granted());

        neverBoth(a, b, Duration.ofSeconds(3));
        assertThat(a.granted() || b.granted()).isTrue();
    }

    @Test
    void once_the_holder_stops_the_other_sends_within_a_second_and_the_lease_does_not_come_back() throws Exception {
        var a = lease("a");
        a.start();
        await().atMost(Duration.ofSeconds(2)).until(a::granted);
        var b = lease("b");
        b.start();
        Thread.sleep(500);
        assertThat(b.granted()).isFalse();

        var stopped = System.nanoTime();
        a.stop();
        assertThat(a.granted()).isFalse();
        await().atMost(Duration.ofSeconds(1)).pollInterval(SAMPLE).until(b::granted);
        assertThat(Duration.ofNanos(System.nanoTime() - stopped)).isLessThan(Duration.ofSeconds(1));

        neverBoth(a, b, Duration.ofSeconds(2));
        assertThat(a.granted()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT owner FROM sender_lease", String.class)).isEqualTo("b");
    }

    /**
     * The holder's last renewal reaches the database, its answer comes back late, then the database is gone for it.
     * Its permission runs from when it sent that renewal, so it is silent before the lease expires for the other.
     */
    @Test
    void a_holder_cut_off_from_the_database_is_silent_before_the_other_takes_over_within_the_lease() throws Exception {
        var slowAnswerThenGone = new AtomicBoolean(false);
        var cutOff = new AtomicBoolean(false);
        var a = lease("a", new CutOff(dataSource, slowAnswerThenGone, cutOff, Duration.ofMillis(2_500), thaw));
        a.start();
        await().atMost(Duration.ofSeconds(2)).until(a::granted);
        var b = lease("b");
        b.start();

        slowAnswerThenGone.set(true);
        await().atMost(Duration.ofSeconds(3)).until(cutOff::get);
        var lostAt = System.nanoTime();

        var deadline = lostAt + props.lease().duration().plusSeconds(2).toNanos();
        long silentAfter = -1;
        long tookOverAfter = -1;
        while (System.nanoTime() - deadline < 0 && tookOverAfter < 0) {
            var now = System.nanoTime();
            assertThat(a.granted() && b.granted()).as("both instances may send").isFalse();
            if (silentAfter < 0 && !a.granted()) {
                silentAfter = now - lostAt;
            }
            if (b.granted()) {
                tookOverAfter = now - lostAt;
            }
            Thread.sleep(SAMPLE);
        }
        assertThat(silentAfter).as("the holder went silent").isNotNegative();
        assertThat(Duration.ofNanos(silentAfter)).isLessThanOrEqualTo(props.lease().sendWindow().plusMillis(200));
        assertThat(tookOverAfter).as("the other took over").isPositive();
        assertThat(Duration.ofNanos(tookOverAfter)).isLessThanOrEqualTo(props.lease().duration().plusSeconds(1));
    }

    /**
     * Hands out connections as the database does until told to answer slowly once: that statement runs, its answer
     * is held back, and from then on no connection is to be had until the test ends.
     */
    private static final class CutOff extends DelegatingDataSource {

        private final AtomicBoolean slowAnswerThenGone;
        private final AtomicBoolean cutOff;
        private final Duration slowAnswer;
        private final CountDownLatch thaw;

        CutOff(DataSource target, AtomicBoolean slowAnswerThenGone, AtomicBoolean cutOff, Duration slowAnswer, CountDownLatch thaw) {
            super(target);
            this.slowAnswerThenGone = slowAnswerThenGone;
            this.cutOff = cutOff;
            this.slowAnswer = slowAnswer;
            this.thaw = thaw;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            if (cutOff.get()) {
                try {
                    thaw.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var connection = super.getConnection();
            var slow = slowAnswerThenGone.getAndSet(false);
            if (!slow) {
                return connection;
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                    cutOff.set(true);
                    Thread.sleep(slowAnswer);
                }
                try {
                    return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }
    }
}
