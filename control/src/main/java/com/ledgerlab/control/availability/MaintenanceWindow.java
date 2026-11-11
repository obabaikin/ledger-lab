package com.ledgerlab.control.availability;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A declared maintenance window.")
public record MaintenanceWindow(Instant from, Instant to) {
}
