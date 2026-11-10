package com.ledgerlab.control.runs;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.chaos.ChaosRepository;
import com.ledgerlab.control.gateway.GatewayClient;
import com.ledgerlab.control.http.Reply;
import com.ledgerlab.control.ledger.LedgerClient;
import com.ledgerlab.control.reporting.ReportingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RunCleanup {

    private static final Logger log = LoggerFactory.getLogger(RunCleanup.class);

    private final RunRepository runs;
    private final LedgerClient ledger;
    private final GatewayClient gateway;
    private final ReportingClient reporting;
    private final ChaosRepository chaos;
    private final ControlProperties props;

    public RunCleanup(
        RunRepository runs,
        LedgerClient ledger,
        GatewayClient gateway,
        ReportingClient reporting,
        ChaosRepository chaos,
        ControlProperties props
    ) {
        this.runs = runs;
        this.ledger = ledger;
        this.gateway = gateway;
        this.reporting = reporting;
        this.chaos = chaos;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${control.cleanup.interval}", initialDelayString = "${control.cleanup.interval}")
    public int purge() throws InterruptedException {
        var purged = 0;
        for (var runId : runs.purgeable(props.cleanup().retention())) {
            if (deleted(ledger.delete(runId)) && deleted(gateway.delete(runId)) && deleted(reporting.delete(runId))) {
                purged += runs.markPurged(runId);
            } else {
                log.warn("Run {}: bank data not deleted yet, retried in {}", runId, props.cleanup().interval());
            }
        }
        if (purged > 0) {
            log.info("Purged the bank data of {} runs", purged);
        }
        chaos.deleteOlderThan(props.cleanup().retention());
        return purged;
    }

    private static boolean deleted(Reply<?> reply) {
        return reply instanceof Reply.Answered<?> || (reply instanceof Reply.Refused<?> refused && refused.status() == HttpStatus.NOT_FOUND.value());
    }
}
