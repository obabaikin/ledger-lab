package com.ledgerlab.control.entry;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class MailjetClient {

    public static final String SEND_PATH = "/v3.1/send";
    public static final String TRACKING_OFF = "disabled";
    private static final String SENDER_NAME = "Ledger Lab";

    private final ControlProperties.Mail mail;
    private final ServiceHttp http;

    public MailjetClient(ControlProperties props, ObjectMapper objectMapper) {
        this.mail = props.mail();
        var credentials = Base64.getEncoder().encodeToString((mail.apiKey() + ":" + mail.apiSecret()).getBytes(StandardCharsets.UTF_8));
        this.http = new ServiceHttp(mail.baseUrl(), mail.requestTimeout(), objectMapper, Map.of(HttpHeaders.AUTHORIZATION, "Basic " + credentials));
    }

    public boolean configured() {
        return !mail.apiKey().isEmpty();
    }

    /** Tracking stays off: a tracked link would pass the invite token through the provider's redirect. */
    public Reply<Object> send(String to, String subject, String text) throws InterruptedException {
        return http.send(
            "POST",
            SEND_PATH,
            Map.of(
                "Messages", List.of(
                    Map.of(
                        "From", Map.of("Email", mail.from(), "Name", SENDER_NAME),
                        "To", List.of(Map.of("Email", to)),
                        "Subject", subject,
                        "TextPart", text,
                        "TrackClicks", TRACKING_OFF,
                        "TrackOpens", TRACKING_OFF))));
    }
}
