package com.ledgerlab.control.api

import com.ledgerlab.control.chaos.ChaosNotAvailableException
import com.ledgerlab.control.chaos.ChaosUsedRecentlyException
import com.ledgerlab.control.chaos.LedgerDidNotAnswerException
import com.ledgerlab.control.chaos.NoRunningRunException
import com.ledgerlab.control.entry.DailyLimitException
import com.ledgerlab.control.entry.InvalidEmailException
import com.ledgerlab.control.entry.InviteNotValidException
import com.ledgerlab.control.entry.SessionNotFoundException
import com.ledgerlab.control.entry.VerificationFailedException
import com.ledgerlab.control.entry.VerifierBusyException
import com.ledgerlab.control.runs.InvalidRunRequestException
import com.ledgerlab.control.runs.QueueFullException
import com.ledgerlab.control.runs.QuotaExceededException
import com.ledgerlab.control.runs.ReportNotReadyException
import com.ledgerlab.control.runs.RequestIdConflictException
import com.ledgerlab.control.runs.RunNotFinalException
import com.ledgerlab.control.runs.RunNotFoundException
import com.ledgerlab.control.runs.RunNotStartedException
import com.ledgerlab.control.runs.StartsBusyException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(InviteNotValidException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun inviteNotValid() = ApiError(ControlErrors.INVITE_NOT_VALID)

    @ExceptionHandler(SessionNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun sessionNotFound() = ApiError(ControlErrors.SESSION_NOT_FOUND)

    @ExceptionHandler(RunNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun runNotFound() = ApiError(ControlErrors.RUN_NOT_FOUND)

    @ExceptionHandler(InvalidRunRequestException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(e: InvalidRunRequestException) = ApiError(checkNotNull(e.message))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadableBody() = ApiError(ControlErrors.INVALID_REQUEST_BODY)

    // An id that is not a UUID cannot name an existing session or run, so it gets the same 404.
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun malformedId(e: MethodArgumentTypeMismatchException): ResponseEntity<ApiError> = when (e.name) {
        SESSION_ID -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError(ControlErrors.SESSION_NOT_FOUND))
        RUN_ID -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError(ControlErrors.RUN_NOT_FOUND))
        else -> throw e
    }

    @ExceptionHandler(RequestIdConflictException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    fun requestIdConflict() = ApiError(ControlErrors.REQUEST_ID_CONFLICT)

    @ExceptionHandler(RunNotFinalException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun runNotFinal() = ApiError(ControlErrors.RUN_NOT_FINAL)

    @ExceptionHandler(QuotaExceededException::class)
    fun quotaExceeded(e: QuotaExceededException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .apply { e.retryAfterSeconds?.let { header(HttpHeaders.RETRY_AFTER, it.coerceAtLeast(1).toString()) } }
            .body(ApiError(ControlErrors.RUN_QUOTA_EXCEEDED))

    @ExceptionHandler(StartsBusyException::class)
    fun startsBusy(): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, BUSY_RETRY_AFTER_SECONDS.toString())
            .body(ApiError(ControlErrors.BUSY))

    @ExceptionHandler(QueueFullException::class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    fun queueFull() = ApiError(ControlErrors.RUN_QUEUE_FULL)

    @ExceptionHandler(RunNotStartedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun runNotStarted() = ApiError(ControlErrors.RUN_NOT_STARTED)

    @ExceptionHandler(ReportNotReadyException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun reportNotReady() = ApiError(ControlErrors.REPORT_NOT_READY)

    @ExceptionHandler(ChaosNotAvailableException::class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    fun chaosNotAvailable() = ApiError(ControlErrors.CHAOS_NOT_AVAILABLE)

    @ExceptionHandler(NoRunningRunException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun noRunningRun() = ApiError(ControlErrors.NO_RUNNING_RUN)

    @ExceptionHandler(ChaosUsedRecentlyException::class)
    fun chaosUsedRecently(e: ChaosUsedRecentlyException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
            .body(ApiError(ControlErrors.CHAOS_USED_RECENTLY))

    @ExceptionHandler(LedgerDidNotAnswerException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun ledgerDidNotAnswer() = ApiError(ControlErrors.LEDGER_DID_NOT_ANSWER)

    @ExceptionHandler(InvalidEmailException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidEmail() = ApiError(ControlErrors.INVALID_EMAIL)

    @ExceptionHandler(VerificationFailedException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun verificationFailed() = ApiError(ControlErrors.VERIFICATION_FAILED)

    @ExceptionHandler(VerifierBusyException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun verifierBusy() = ApiError(ControlErrors.BUSY)

    @ExceptionHandler(DailyLimitException::class)
    fun dailyLimit(e: DailyLimitException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
            .body(ApiError(ControlErrors.DAILY_LIMIT))

    private companion object {
        const val SESSION_ID = "sessionId"
        const val RUN_ID = "runId"
        const val BUSY_RETRY_AFTER_SECONDS = 1
    }
}
