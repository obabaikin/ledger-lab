package com.ledgerlab.control.api;

import com.ledgerlab.control.entry.AddressDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/address-deletions")
public class AddressDeletionController {

    private final AddressDeletionService deletions;

    public AddressDeletionController(AddressDeletionService deletions) {
        this.deletions = deletions;
    }

    @Operation(summary = "Delete the address a link was mailed to")
    @ApiResponse(responseCode = "202", description = "Deletion is set")
    @ApiResponse(responseCode = "400", description = "No token, or unreadable body", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No address is stored for this link", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AddressDeletionAccepted requestDeletion(@RequestBody AddressDeletionRequest request) {
        var deletion = deletions.request(request.token());
        return new AddressDeletionAccepted(deletion.deleteAt(), deletion.alreadyAsked());
    }
}
