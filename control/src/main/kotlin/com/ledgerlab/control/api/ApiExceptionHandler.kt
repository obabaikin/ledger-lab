package com.ledgerlab.control.api

import com.ledgerlab.control.entry.InviteNotValidException
import com.ledgerlab.control.entry.SessionNotFoundException
import com.ledgerlab.control.runs.InvalidRunOptionsException
import com.ledgerlab.control.runs.QueueFullException
import com.ledgerlab.control.runs.QuotaExceededException
import com.ledgerlab.control.runs.RequestIdConflictException
import com.ledgerlab.control.runs.RunNotFoundException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

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

    @ExceptionHandler(InvalidRunOptionsException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidOptions(e: InvalidRunOptionsException) = ApiError(checkNotNull(e.message))

    @ExceptionHandler(RequestIdConflictException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    fun requestIdConflict() = ApiError(ControlErrors.REQUEST_ID_CONFLICT)

    @ExceptionHandler(QuotaExceededException::class)
    fun quotaExceeded(e: QuotaExceededException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .apply { e.retryAfterSeconds?.let { header(HttpHeaders.RETRY_AFTER, it.coerceAtLeast(1).toString()) } }
            .body(ApiError(ControlErrors.RUN_QUOTA_EXCEEDED))

    @ExceptionHandler(QueueFullException::class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    fun queueFull() = ApiError(ControlErrors.RUN_QUEUE_FULL)
}
