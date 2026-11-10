package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Who runs the site.")
public record Operator(String name, String address, String email) {
}
