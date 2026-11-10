package com.ledgerlab.control.api;

import com.ledgerlab.control.chaos.ChaosService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChaosController {

    public static final String CHAOS_PATH = "/api/sessions/{sessionId}/chaos";

    private final ChaosService chaos;

    public ChaosController(ChaosService chaos) {
        this.chaos = chaos;
    }

    @Operation(summary = "Crash every ledger instance during a run")
    @ApiResponse(responseCode = "202", description = "Crashed; the instances restart and the run goes on")
    @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "The session has no running run", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
        responseCode = "429", description = "Chaos was used on the cluster recently (Retry-After says when)",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds until chaos is available again", schema = @Schema(type = "integer")),
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "501", description = "Not available in this deployment", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "No ledger instance answered", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(CHAOS_PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChaosView chaos(@PathVariable UUID sessionId) throws InterruptedException, ExecutionException {
        var event = chaos.crash(sessionId);
        return new ChaosView(event.at(), event.instances());
    }
}
