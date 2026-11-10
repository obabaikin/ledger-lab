package com.ledgerlab.ledger.chaos;

import java.time.Duration;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.http.HttpStatus;

@Endpoint(id = CrashEndpoint.ID)
public class CrashEndpoint {

    public static final String ID = "crash";

    private final Halter halter;
    private final Duration haltDelay;

    public CrashEndpoint(Halter halter, Duration haltDelay) {
        this.halter = halter;
        this.haltDelay = haltDelay;
    }

    @WriteOperation
    public WebEndpointResponse<Void> crash() {
        // The answer must leave first: a connection cut by the halt would read to control as an instance that did not crash.
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(haltDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            halter.halt();
        });
        return new WebEndpointResponse<>(HttpStatus.ACCEPTED.value());
    }
}
