package com.ledgerlab.control.api;

import com.ledgerlab.control.chaos.ChaosNotAvailableException;
import com.ledgerlab.control.chaos.ChaosUsedRecentlyException;
import com.ledgerlab.control.chaos.LedgerDidNotAnswerException;
import com.ledgerlab.control.chaos.NoRunningRunException;
import com.ledgerlab.control.entry.DailyLimitException;
import com.ledgerlab.control.entry.InvalidEmailException;
import com.ledgerlab.control.entry.InviteNotValidException;
import com.ledgerlab.control.entry.SessionNotFoundException;
import com.ledgerlab.control.entry.TokenRequiredException;
import com.ledgerlab.control.entry.UnknownLinkException;
import com.ledgerlab.control.entry.VerificationFailedException;
import com.ledgerlab.control.entry.VerifierBusyException;
import com.ledgerlab.control.runs.InvalidRunRequestException;
import com.ledgerlab.control.runs.QueueFullException;
import com.ledgerlab.control.runs.QuotaExceededException;
import com.ledgerlab.control.runs.ReportNotReadyException;
import com.ledgerlab.control.runs.RequestIdConflictException;
import com.ledgerlab.control.runs.RunNotFinalException;
import com.ledgerlab.control.runs.RunNotFoundException;
import com.ledgerlab.control.runs.RunNotStartedException;
import com.ledgerlab.control.runs.StartsBusyException;
import java.util.Objects;
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
    private static final String RUN_ID = "runId";
    private static final int BUSY_RETRY_AFTER_SECONDS = 1;

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

    @ExceptionHandler(RunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError runNotFound() {
        return new ApiError(ControlErrors.RUN_NOT_FOUND);
    }

    @ExceptionHandler(InvalidRunRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidRequest(InvalidRunRequestException e) {
        return new ApiError(Objects.requireNonNull(e.getMessage(), "the message of an invalid run request"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError unreadableBody() {
        return new ApiError(ControlErrors.INVALID_REQUEST_BODY);
    }

    // An id that is not a UUID cannot name an existing session or run, so it gets the same 404.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> malformedId(MethodArgumentTypeMismatchException e) {
        return switch (e.getName()) {
            case SESSION_ID -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ControlErrors.SESSION_NOT_FOUND));
            case RUN_ID -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ControlErrors.RUN_NOT_FOUND));
            default -> throw e;
        };
    }

    @ExceptionHandler(RequestIdConflictException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ApiError requestIdConflict() {
        return new ApiError(ControlErrors.REQUEST_ID_CONFLICT);
    }

    @ExceptionHandler(RunNotFinalException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError runNotFinal() {
        return new ApiError(ControlErrors.RUN_NOT_FINAL);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiError> quotaExceeded(QuotaExceededException e) {
        var answer = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        var retryAfterSeconds = e.retryAfterSeconds();
        if (retryAfterSeconds != null) {
            answer.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(retryAfterSeconds, 1)));
        }
        return answer.body(new ApiError(ControlErrors.RUN_QUOTA_EXCEEDED));
    }

    @ExceptionHandler(StartsBusyException.class)
    public ResponseEntity<ApiError> startsBusy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, Integer.toString(BUSY_RETRY_AFTER_SECONDS))
            .body(new ApiError(ControlErrors.BUSY));
    }

    @ExceptionHandler(QueueFullException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiError queueFull() {
        return new ApiError(ControlErrors.RUN_QUEUE_FULL);
    }

    @ExceptionHandler(RunNotStartedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError runNotStarted() {
        return new ApiError(ControlErrors.RUN_NOT_STARTED);
    }

    @ExceptionHandler(ReportNotReadyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError reportNotReady() {
        return new ApiError(ControlErrors.REPORT_NOT_READY);
    }

    @ExceptionHandler(ChaosNotAvailableException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public ApiError chaosNotAvailable() {
        return new ApiError(ControlErrors.CHAOS_NOT_AVAILABLE);
    }

    @ExceptionHandler(NoRunningRunException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError noRunningRun() {
        return new ApiError(ControlErrors.NO_RUNNING_RUN);
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
}
