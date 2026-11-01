package ru.calorica.platform.api

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class ApiExceptionHandler(private val problems: ApiProblems) : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleExceptionInternal(
        ex: Exception, body: Any?, headers: HttpHeaders, statusCode: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any> = ResponseEntity.status(statusCode).headers(headers)
        .body(problems.problem(statusCode.value(), (request as ServletWebRequest).request))

    @ExceptionHandler(ApiFailure::class)
    fun expected(ex: ApiFailure, request: HttpServletRequest): ResponseEntity<Any> =
        ResponseEntity.status(ex.status).headers(org.springframework.http.HttpHeaders().apply {
            if (ex.status == 429) set("Retry-After", "1")
        }).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(problems.problem(ex.status, request, ex.code, ex.errors))

    @ExceptionHandler(org.springframework.dao.QueryTimeoutException::class,
        org.springframework.dao.CannotAcquireLockException::class,
        org.springframework.dao.DataAccessResourceFailureException::class,
        org.springframework.transaction.TransactionTimedOutException::class)
    fun unavailable(ex: Exception, request: HttpServletRequest): ResponseEntity<Any> =
        ResponseEntity.status(503).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(problems.problem(503, request))

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun forbidden(ex: Exception, request: HttpServletRequest): ResponseEntity<Any> =
        ResponseEntity.status(403).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(problems.problem(403, request))

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception, request: HttpServletRequest): ResponseEntity<Any> {
        // No exception message/SQL/parameters in logs or responses.
        log.error("request id={} failed type={}", request.getAttribute(RequestIdFilter.ATTRIBUTE), ex.javaClass.simpleName)
        return ResponseEntity.internalServerError().body(problems.problem(500, request))
    }
}
