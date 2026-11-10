package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Taken; the same for any address, whether a mail goes out or not.")
public record InviteRequestAccepted() {
}
