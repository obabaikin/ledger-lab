package com.ledgerlab.generator.load;

import com.ledgerlab.generator.ApiError;
import com.ledgerlab.generator.GeneratorErrors;
import com.ledgerlab.generator.journal.ClientTotalsStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/load")
public class LoadController {

    private static final Logger log = LoggerFactory.getLogger(LoadController.class);

    private final Load load;
    private final ResponseMinutes minutes;
    private final ClientTotalsStore totals;

    public LoadController(Load load, ResponseMinutes minutes, ClientTotalsStore totals) {
        this.load = load;
        this.minutes = minutes;
        this.totals = totals;
    }

    @Operation(summary = "The load now, the clients' counts since this generator started and their totals since the bank started")
    @GetMapping
    public LoadStatus status() {
        return load.status().withTotal(totals.current());
    }

    @Operation(summary = "Raise the load for a while; of several pulses the highest sets the pace")
    @ApiResponse(responseCode = "200", description = "The pulse is in effect")
    @ApiResponse(responseCode = "400", description = "tps or seconds missing, below 1 or above the generator's ceiling", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/pulse")
    public LoadStatus pulse(@RequestBody PulseRequest request) {
        var tps = request.tps();
        var seconds = request.seconds();
        if (tps == null || seconds == null) {
            throw new InvalidPulseException("a pulse needs tps and seconds");
        }
        load.pulse(tps, Duration.ofSeconds(seconds));
        return status();
    }

    @Operation(summary = "The bank's answers to the clients by minute, for the minutes that are over")
    @GetMapping("/minutes")
    public MinutesPage minutes(
        @Parameter(description = "Only minutes after this instant; omit for all the generator keeps")
        @RequestParam(required = false) @Nullable Instant after
    ) {
        return new MinutesPage(minutes.completeAfter(after));
    }

    @Schema(description = "Minutes without a request are left out.")
    public record MinutesPage(List<MinuteResponses> minutes) {
    }

    @ExceptionHandler(InvalidPulseException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidPulse(InvalidPulseException e) {
        log.info("{}", e.getMessage());
        return new ApiError(GeneratorErrors.INVALID_PULSE);
    }
}
