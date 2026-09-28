package ru.calorica.platform.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Component
class ApiProblems(private val mapper: ObjectMapper) {
    fun problem(status: Int, request: HttpServletRequest): ProblemDetail {
        val httpStatus = HttpStatus.resolve(status) ?: HttpStatus.INTERNAL_SERVER_ERROR
        return ProblemDetail.forStatusAndDetail(httpStatus, when (httpStatus.value()) {
            401 -> "Authentication is required."
            403 -> "Access is denied."
            404 -> "Resource not found."
            in 400..499 -> "The request could not be accepted."
            else -> "An internal error occurred."
        }).apply {
            setProperty("code", httpStatus.name)
            setProperty("correlationId", request.getAttribute(RequestIdFilter.ATTRIBUTE))
        }
    }
    fun write(status: Int, request: HttpServletRequest, response: HttpServletResponse) {
        response.status = status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        mapper.writeValue(response.outputStream, problem(status, request))
    }
}

@RestController
class ApiErrorController(private val problems: ApiProblems) : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int) ?: 500
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problems.problem(status, request))
    }
}
