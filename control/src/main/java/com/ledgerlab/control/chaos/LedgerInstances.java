package com.ledgerlab.control.chaos;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.http.ServiceHttp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LedgerInstances {

    private static final String CRASH_PATH = "/actuator/crash";

    private final Map<String, ServiceHttp> instances = new LinkedHashMap<>();

    public LedgerInstances(ControlProperties props, ObjectMapper objectMapper) {
        props.chaos().ledgerInstances().forEach(it ->
            instances.put(it.name(), new ServiceHttp(it.url(), props.chaos().requestTimeout(), objectMapper)));
    }

    /** The names of the instances that answered 2xx, in configured order; the others are taken as not crashed. */
    public List<String> crashAll() throws InterruptedException, ExecutionException {
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var replies = new LinkedHashMap<String, Future<Reply<Object>>>();
            instances.forEach((name, http) -> replies.put(name, calls.submit(() -> http.send("POST", CRASH_PATH))));
            var crashed = new ArrayList<String>();
            for (var reply : replies.entrySet()) {
                if (reply.getValue().get() instanceof Reply.Answered<Object>) {
                    crashed.add(reply.getKey());
                }
            }
            return crashed;
        }
    }
}
