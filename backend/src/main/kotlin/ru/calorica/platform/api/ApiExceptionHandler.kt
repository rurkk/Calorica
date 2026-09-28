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

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception, request: HttpServletRequest): ResponseEntity<Any> {
        // No exception message/SQL/parameters in logs or responses.
        log.error("request id={} failed type={}", request.getAttribute(RequestIdFilter.ATTRIBUTE), ex.javaClass.simpleName)
        return ResponseEntity.internalServerError().body(problems.problem(500, request))
    }
}
