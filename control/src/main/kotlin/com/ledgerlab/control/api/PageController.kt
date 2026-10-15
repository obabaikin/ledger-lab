package com.ledgerlab.control.api

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** The page routes `/s/<sessionId>` itself; the server only hands it the same document as `/`. */
@Hidden
@Controller
class PageController {

    @GetMapping("/s/{sessionId}")
    fun session(): String = "forward:/index.html"
}
