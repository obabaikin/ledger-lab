package com.ledgerlab.control.api;

import com.ledgerlab.control.ControlProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SiteController {

    private final ControlProperties props;

    public SiteController(ControlProperties props) {
        this.props = props;
    }

    @Operation(summary = "The site's settings for the page")
    @ApiResponse(responseCode = "200", description = "The settings")
    @GetMapping("/api/site")
    public SiteView site() {
        var email = props.invites().email();
        var site = props.site();
        var operator = new Operator(site.operatorName(), site.operatorAddress(), site.contactEmail());
        var operatorSet = Stream.of(operator.name(), operator.address(), operator.email()).noneMatch(String::isEmpty);
        return new SiteView(
            props.turnstile().siteKey().isEmpty() ? null : props.turnstile().siteKey(),
            email.lifetime().toDays(),
            email.retention().toDays(),
            email.linksPerAddress(),
            operatorSet ? operator : null,
            site.version());
    }
}
