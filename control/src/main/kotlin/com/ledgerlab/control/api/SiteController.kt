package com.ledgerlab.control.api

import com.ledgerlab.control.ControlProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@Schema(description = "Who runs the site.")
data class Operator(val name: String, val address: String, val email: String)

@Schema(description = "What the start, privacy and imprint pages show.")
data class SiteView(
    @field:Schema(description = "The Turnstile widget's site key; null where the check is off.")
    val turnstileSiteKey: String?,
    @field:Schema(description = "Days a mailed link works.")
    val linkDays: Long,
    @field:Schema(description = "Days without use after which an address is deleted.")
    val retentionDays: Long,
    @field:Schema(description = "Links an address may get in 24 hours.")
    val linksPerDay: Int,
    @field:Schema(description = "null where the deployment has not set them.")
    val operator: Operator?,
)

@RestController
class SiteController(private val props: ControlProperties) {

    @Operation(summary = "The site's settings for the page")
    @ApiResponse(responseCode = "200", description = "The settings")
    @GetMapping("/api/site")
    fun site(): SiteView {
        val email = props.invites.email
        val site = props.site
        return SiteView(
            turnstileSiteKey = props.turnstile.siteKey.ifEmpty { null },
            linkDays = email.lifetime.toDays(),
            retentionDays = email.retention.toDays(),
            linksPerDay = email.linksPerAddress,
            operator = Operator(site.operatorName, site.operatorAddress, site.contactEmail)
                .takeIf { listOf(it.name, it.address, it.email).all(String::isNotEmpty) },
        )
    }
}
