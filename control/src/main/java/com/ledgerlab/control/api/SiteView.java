package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "What the start, privacy and imprint pages show.")
public record SiteView(
    @Schema(description = "The Turnstile widget's site key; null where the check is off.", nullable = true)
    @Nullable String turnstileSiteKey,
    @Schema(description = "Days a mailed link works.")
    long linkDays,
    @Schema(description = "Days without use after which an address is deleted.")
    long retentionDays,
    @Schema(description = "Links an address may get in 24 hours.")
    int linksPerDay,
    @Schema(description = "null where the deployment has not set them.", nullable = true)
    @Nullable Operator operator,
    @Schema(description = "The version the site was deployed with.")
    String version
) {
}
