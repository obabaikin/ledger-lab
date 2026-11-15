package com.ledgerlab.gateway.api;

import com.ledgerlab.gateway.service.UnknownPeriodException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {PeriodController.class, StatementController.class})
public class PeriodErrors {

    private static final Logger log = LoggerFactory.getLogger(PeriodErrors.class);

    @ExceptionHandler(UnknownPeriodException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError unknownPeriod(UnknownPeriodException e) {
        log.info("{}", e.getMessage());
        return new ApiError(GatewayErrors.UNKNOWN_PERIOD);
    }
}
