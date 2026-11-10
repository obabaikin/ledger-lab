package com.ledgerlab.reporting.api;

import com.ledgerlab.reporting.domain.RunStats;
import com.ledgerlab.reporting.persistence.RunStatsRepository;
import com.ledgerlab.reporting.persistence.TransferStatsRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/runs/{runId}")
public class RunStatsController {

    private final RunStatsRepository stats;
    private final TransferStatsRepository transfers;

    public RunStatsController(RunStatsRepository stats, TransferStatsRepository transfers) {
        this.stats = stats;
        this.transfers = transfers;
    }

    @Operation(summary = "A run's numbers from the bank's events")
    @ApiResponse(responseCode = "200", description = "The snapshot; a run with no events yet has zeros")
    @GetMapping("/stats")
    public RunStats stats(@PathVariable UUID runId) {
        return stats.snapshot(runId);
    }

    @Operation(summary = "Delete a run's numbers")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No events of this run")
    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable UUID runId) {
        return transfers.deleteRun(runId) > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
