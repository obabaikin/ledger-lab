package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The page routes its addresses itself; the server only hands it the same document as `/`. */
@Hidden
@Controller
public class PageController {

    @GetMapping({"/s/{sessionId}", "/privacy", "/impressum", "/delete"})
    public String page() {
        return "forward:/index.html";
    }
}
