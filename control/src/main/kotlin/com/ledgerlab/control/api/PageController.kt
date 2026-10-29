package com.ledgerlab.control.api

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView

/** The page routes its addresses itself; the server only hands it the same document as `/`. */
@Hidden
@Controller
class PageController {

    @GetMapping("/s/{sessionId}", "/privacy", "/impressum", "/delete")
    fun page(): String = "forward:/index.html"
}

@Component
class UnknownPageRedirect : ErrorViewResolver {

    override fun resolveErrorView(request: HttpServletRequest, status: HttpStatus, model: Map<String, Any>): ModelAndView? {
        val path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String ?: return null
        if (status != HttpStatus.NOT_FOUND || path == START || path == API || path.startsWith("$API/")) return null
        val target = SESSION_ADDRESS.find(path)?.value ?: START
        val query = (request.getAttribute(RequestDispatcher.ERROR_QUERY_STRING) as? String)?.let { "?$it" }.orEmpty()
        return ModelAndView(RedirectView("$target$query").apply { setExpandUriTemplateVariables(false) })
    }

    companion object {
        private const val API = "/api"
        private const val START = "/"
        private val SESSION_ADDRESS = Regex("^/s/[0-9a-fA-F-]{36}(?=/)")
    }
}
