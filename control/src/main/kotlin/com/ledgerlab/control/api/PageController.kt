package com.ledgerlab.control.api

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.ModelAndView

/** The page routes `/s/<sessionId>` itself; the server only hands it the same document as `/`. */
@Hidden
@Controller
class PageController {

    @GetMapping("/s/{sessionId}")
    fun session(): String = "forward:/index.html"
}

/** A browser that asks for an address the page does not have lands on the start instead of Spring's error page. */
@Component
class UnknownPageRedirect : ErrorViewResolver {

    override fun resolveErrorView(request: HttpServletRequest, status: HttpStatus, model: Map<String, Any>): ModelAndView? {
        val path = model[PATH_ATTRIBUTE] as? String ?: return null
        return if (status == HttpStatus.NOT_FOUND && !path.startsWith(API_PREFIX)) ModelAndView(REDIRECT_TO_START) else null
    }

    companion object {
        private const val PATH_ATTRIBUTE = "path"
        private const val API_PREFIX = "/api/"
        private const val REDIRECT_TO_START = "redirect:/"
    }
}
