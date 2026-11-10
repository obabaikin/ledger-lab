package com.ledgerlab.control.entry;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class TurnstileClient {

    public static final String SITEVERIFY_PATH = "/turnstile/v0/siteverify";

    private final String secret;
    private final ServiceHttp http;

    public record Outcome(boolean success) {
    }

    public TurnstileClient(ControlProperties props, ObjectMapper objectMapper) {
        this.secret = props.turnstile().secret();
        this.http = new ServiceHttp(props.turnstile().baseUrl(), props.turnstile().requestTimeout(), objectMapper);
    }

    public void verify(@Nullable String token) throws InterruptedException {
        if (secret.isEmpty()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new VerificationFailedException();
        }
        var reply = http.call("POST", SITEVERIFY_PATH, Outcome.class, Map.of("secret", secret, "response", token));
        if (!(reply instanceof Reply.Answered<Outcome>(var status, var outcome))) {
            throw new VerifierBusyException();
        }
        if (outcome == null || !outcome.success()) {
            throw new VerificationFailedException();
        }
    }
}
