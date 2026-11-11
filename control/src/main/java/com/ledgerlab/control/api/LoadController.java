package com.ledgerlab.control.api;

import com.ledgerlab.control.generator.Pulse;
import com.ledgerlab.control.load.LoadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoadController {

    public static final String LOAD_PATH = "/api/sessions/{sessionId}/load";

    private final LoadService load;

    public LoadController(LoadService load) {
        this.load = load;
    }

    @Operation(summary = "Raise the bank's load for a while")
    @ApiResponse(responseCode = "202", description = "The pulse runs until the time given; then the load goes back to its base")
    @ApiResponse(responseCode = "400", description = "Unreadable body, or a speed or length not among the options", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "The session's invite expired or was switched off", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
        responseCode = "429", description = "A pulse runs, or ended recently (Retry-After says when)",
        headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds until a pulse may be started again", schema = @Schema(type = "integer")),
        content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "The generator did not confirm the pulse; without an answer it is taken as running", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(LOAD_PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Pulse load(@PathVariable UUID sessionId, @RequestBody LoadRequest request) throws InterruptedException {
        return load.raise(sessionId, request.tps(), request.seconds());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError unreadableLoad() {
        return new ApiError(ControlErrors.INVALID_LOAD);
    }
}
