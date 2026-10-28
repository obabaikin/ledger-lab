package com.ledgerlab.control.api

import com.ledgerlab.control.entry.AddressDeletionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@Schema(description = "A mailed link whose address is to be deleted.")
data class AddressDeletionRequest(
    @field:Schema(description = "The link's ref, from any mail sent to the address.", requiredMode = Schema.RequiredMode.REQUIRED)
    val token: String?,
)

@Schema(description = "Deletion is set.")
data class AddressDeletionAccepted(
    @field:Schema(description = "From when the address is deleted; asking again keeps the first time.")
    val deleteAt: Instant,
    @field:Schema(description = "Whether an earlier request had already set the deletion.")
    val alreadyAsked: Boolean,
)

@RestController
@RequestMapping("/api/address-deletions")
class AddressDeletionController(private val deletions: AddressDeletionService) {

    @Operation(summary = "Delete the address a link was mailed to")
    @ApiResponse(responseCode = "202", description = "Deletion is set")
    @ApiResponse(responseCode = "400", description = "No token, or unreadable body", content = [Content(schema = Schema(implementation = ApiError::class))])
    @ApiResponse(responseCode = "404", description = "No address is stored for this link", content = [Content(schema = Schema(implementation = ApiError::class))])
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestDeletion(@RequestBody request: AddressDeletionRequest): AddressDeletionAccepted {
        val deletion = deletions.request(request.token)
        return AddressDeletionAccepted(deletion.deleteAt, deletion.alreadyAsked)
    }
}
