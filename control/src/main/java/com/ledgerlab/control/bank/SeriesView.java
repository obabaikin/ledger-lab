package com.ledgerlab.control.bank;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The bank's charts from monitoring, on one grid of instants.")
public record SeriesView(
    @Schema(description = "The first instant of the grid.")
    Instant from,
    long stepSeconds,
    @Schema(description = "Instants on the grid.")
    int points,
    @Schema(description = "Empty if monitoring could not be read.")
    List<Series> series
) {

    public record Series(
        SeriesName name,
        @ArraySchema(
            arraySchema = @Schema(description = "One value per instant of the grid; null where monitoring has none."),
            schema = @Schema(types = {"number", "null"}, format = "double"))
        List<@Nullable Double> values
    ) {
    }
}
