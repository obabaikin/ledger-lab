package com.ledgerlab.generator;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record JournalPage(List<JournalEntryView> items, @Schema(nullable = true) @Nullable Long next) {
}
