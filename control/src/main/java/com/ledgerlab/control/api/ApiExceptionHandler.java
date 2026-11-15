package com.ledgerlab.control.api;

import com.ledgerlab.control.bank.InvalidLimitException;
import com.ledgerlab.control.bank.InvalidRangeException;
import com.ledgerlab.control.bank.UnknownPeriodException;
import com.ledgerlab.control.chaos.ChaosNotAvailableException;
import com.ledgerlab.control.chaos.ChaosUsedRecentlyException;
import com.ledgerlab.control.chaos.LedgerDidNotAnswerException;
import com.ledgerlab.control.entry.DailyLimitException;
import com.ledgerlab.control.entry.InvalidEmailException;
import com.ledgerlab.control.entry.InviteNotValidException;
import com.ledgerlab.control.entry.SessionNotFoundException;
import com.ledgerlab.control.entry.TokenRequiredException;
import com.ledgerlab.control.entry.UnknownLinkException;
import com.ledgerlab.control.entry.VerificationFailedException;
import com.ledgerlab.control.entry.VerifierBusyException;
import com.ledgerlab.control.load.GeneratorDidNotAnswerException;
import com.ledgerlab.control.load.InvalidLoadException;
import com.ledgerlab.control.load.LoadRunningException;
import com.ledgerlab.control.load.LoadUsedRecentlyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String SESSION_ID = "sessionId";
    private static final String PERIOD_START = "periodStart";
    private static final String LIMIT = "limit";

    @ExceptionHandler(InviteNotValidException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError inviteNotValid() {
        return new ApiError(ControlErrors.INVITE_NOT_VALID);
    }

    @ExceptionHandler(SessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError sessionNotFound() {
        return new ApiError(ControlErrors.SESSION_NOT_FOUND);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError unreadableBody() {
        return new ApiError(ControlErrors.INVALID_REQUEST_BODY);
    }

    // An id that is not a UUID cannot name an existing session, nor can a start that is no instant name a period: the same 404.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> malformedId(MethodArgumentTypeMismatchException e) {
        return switch (e.getName()) {
            case SESSION_ID -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ControlErrors.SESSION_NOT_FOUND));
            case PERIOD_START -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ControlErrors.UNKNOWN_PERIOD));
            case LIMIT -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ControlErrors.INVALID_LIMIT));
            default -> throw e;
        };
    }

    @ExceptionHandler(ChaosNotAvailableException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public ApiError chaosNotAvailable() {
        return new ApiError(ControlErrors.CHAOS_NOT_AVAILABLE);
    }

    @ExceptionHandler(ChaosUsedRecentlyException.class)
    public ResponseEntity<ApiError> chaosUsedRecently(ChaosUsedRecentlyException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
            .body(new ApiError(ControlErrors.CHAOS_USED_RECENTLY));
    }

    @ExceptionHandler(LedgerDidNotAnswerException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError ledgerDidNotAnswer() {
        return new ApiError(ControlErrors.LEDGER_DID_NOT_ANSWER);
    }

    @ExceptionHandler(InvalidLoadException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidLoad() {
        return new ApiError(ControlErrors.INVALID_LOAD);
    }

    @ExceptionHandler(LoadRunningException.class)
    public ResponseEntity<ApiError> loadRunning(LoadRunningException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
            .body(new ApiError(ControlErrors.LOAD_RUNNING));
    }

    @ExceptionHandler(LoadUsedRecentlyException.class)
    public ResponseEntity<ApiError> loadUsedRecently(LoadUsedRecentlyException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
            .body(new ApiError(ControlErrors.LOAD_USED_RECENTLY));
    }

    @ExceptionHandler(GeneratorDidNotAnswerException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError generatorDidNotAnswer() {
        return new ApiError(ControlErrors.GENERATOR_DID_NOT_ANSWER);
    }

    @ExceptionHandler(InvalidEmailException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidEmail() {
        return new ApiError(ControlErrors.INVALID_EMAIL);
    }

    @ExceptionHandler(VerificationFailedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError verificationFailed() {
        return new ApiError(ControlErrors.VERIFICATION_FAILED);
    }

    @ExceptionHandler(VerifierBusyException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError verifierBusy() {
        return new ApiError(ControlErrors.BUSY);
    }

    @ExceptionHandler(DailyLimitException.class)
    public ResponseEntity<ApiError> dailyLimit(DailyLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
            .body(new ApiError(ControlErrors.DAILY_LIMIT));
    }

    @ExceptionHandler(TokenRequiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError tokenRequired() {
        return new ApiError(ControlErrors.TOKEN_REQUIRED);
    }

    @ExceptionHandler(UnknownLinkException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError unknownLink() {
        return new ApiError(ControlErrors.UNKNOWN_LINK);
    }

    @ExceptionHandler(UnknownPeriodException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError unknownPeriod() {
        return new ApiError(ControlErrors.UNKNOWN_PERIOD);
    }

    @ExceptionHandler(InvalidLimitException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidLimit() {
        return new ApiError(ControlErrors.INVALID_LIMIT);
    }

    @ExceptionHandler(InvalidRangeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidRange() {
        return new ApiError(ControlErrors.INVALID_RANGE);
    }
}
