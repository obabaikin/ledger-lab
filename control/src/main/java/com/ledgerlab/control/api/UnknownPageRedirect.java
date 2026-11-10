package com.ledgerlab.control.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Component
public class UnknownPageRedirect implements ErrorViewResolver {

    private static final String API = "/api";
    private static final String START = "/";
    private static final Pattern SESSION_ADDRESS = Pattern.compile("^/s/[0-9a-fA-F-]{36}(?=/)");

    @Override
    public @Nullable ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        if (!(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String path)) {
            return null;
        }
        if (status != HttpStatus.NOT_FOUND || path.equals(START) || path.equals(API) || path.startsWith(API + "/")) {
            return null;
        }
        var session = SESSION_ADDRESS.matcher(path);
        var target = session.find() ? session.group() : START;
        var query = request.getAttribute(RequestDispatcher.ERROR_QUERY_STRING) instanceof String queryString ? "?" + queryString : "";
        var redirect = new RedirectView(target + query);
        redirect.setExpandUriTemplateVariables(false);
        return new ModelAndView(redirect);
    }
}
