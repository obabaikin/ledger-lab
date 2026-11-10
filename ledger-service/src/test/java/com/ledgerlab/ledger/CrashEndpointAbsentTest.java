package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.ledger.chaos.CrashEndpoint;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.ApplicationContext;

@AutoConfigureTestRestTemplate
class CrashEndpointAbsentTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired ApplicationContext context;

    // Checked without a POST: were the endpoint there, a POST would halt the test JVM.
    @Test
    void crash_endpoint_is_absent_unless_chaos_is_enabled() {
        var links = ((Map<?, ?>) http.getForEntity("/actuator", Map.class).getBody().get("_links")).keySet();

        assertThat(links).map(Object::toString).contains("health").doesNotContain(CrashEndpoint.ID);
        assertThat(context.getBeanNamesForType(CrashEndpoint.class)).isEmpty();
    }
}
