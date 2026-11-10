package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A session's runs, newest first.")
public record RunList(List<RunView> items) {
}
