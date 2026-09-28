package ru.calorica.platform.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    private val accessLog = LoggerFactory.getLogger(javaClass)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = UUID.randomUUID().toString()
        request.setAttribute(ATTRIBUTE, id)
        response.setHeader("X-Request-Id", id)
        MDC.put("correlationId", id)
        val start = System.nanoTime()
        try { chain.doFilter(request, response) } finally {
            accessLog.info("request id={} method={} status={} durationMs={}", id, request.method,
                response.status, (System.nanoTime() - start) / 1_000_000)
            MDC.remove("correlationId")
        }
    }
    companion object { const val ATTRIBUTE = "calorica.requestId" }
}
