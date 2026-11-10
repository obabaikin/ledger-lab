package com.ledgerlab.control.api;

import com.ledgerlab.control.entry.EntryService;
import com.ledgerlab.control.entry.InviteNotValidException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(SessionController.SESSIONS_PATH)
public class SessionController {

    public static final String SESSIONS_PATH = "/api/sessions";

    private final EntryService entry;

    public SessionController(EntryService entry) {
        this.entry = entry;
    }

    @Operation(summary = "Open a session with an invite")
    @ApiResponse(responseCode = "201", description = "Session opened", headers = @Header(name = HttpHeaders.LOCATION, description = "The session's address", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Unreadable body", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Unknown, inactive or expired invite", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    public ResponseEntity<SessionCreated> create(@RequestBody CreateSessionRequest request) {
        var sessionId = entry.enter(request.token());
        if (sessionId == null) {
            throw new InviteNotValidException();
        }
        return ResponseEntity.created(URI.create(SESSIONS_PATH + "/" + sessionId)).body(new SessionCreated(sessionId));
    }

    @Operation(summary = "A session's quota and run options")
    @ApiResponse(responseCode = "200", description = "The session")
    @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{sessionId}")
    public SessionView get(@PathVariable UUID sessionId) {
        return SessionView.of(entry.overview(sessionId));
    }
}
