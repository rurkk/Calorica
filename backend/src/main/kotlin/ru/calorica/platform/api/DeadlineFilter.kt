package ru.calorica.platform.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayOutputStream
import java.util.concurrent.*

/** Nonblocking bounded input, container-managed async dispatch and one eight-second HTTP budget. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class DeadlineFilter(private val problems: ApiProblems, private val mapper: ObjectMapper) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val permits = Semaphore(4)
    private val timer = ScheduledThreadPoolExecutor(1, ThreadFactory { runnable ->
        Thread(runnable, "api-deadline").apply { isDaemon = true }
    }).apply { removeOnCancelPolicy = true }
    override fun shouldNotFilter(request: HttpServletRequest) = !request.servletPath.startsWith("/api/v1/")
    override fun shouldNotFilterAsyncDispatch() = false

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.dispatcherType == DispatcherType.ASYNC) {
            val state = request.getAttribute(STATE) as State
            synchronized(state.gate) {
                if (state.finished) return
                state.async = request.startAsync(request, response).apply { timeout = 0 }
                state.active = true
                state.worker = Thread.currentThread()
            }
            val acquired = permits.tryAcquire()
            RequestDeadline.enter(state.end)
            MDC.put("correlationId", state.id)
            try {
                if (!acquired) { state.finish(503); return }
                RequestDeadline.check()
                chain.doFilter(BufferedRequest(request, state.body), state.cached)
                RequestDeadline.check()
                state.finish()
            } catch (failure: Exception) {
                val root = generateSequence<Throwable>(failure) { it.cause }.last()
                log.warn("HTTP worker failed type={} at={}", root.javaClass.simpleName, root.stackTrace.firstOrNull())
                state.finish(503)
            } finally {
                if (acquired) permits.release()
                RequestDeadline.leave(); MDC.remove("correlationId")
                synchronized(state.gate) { state.active = false; state.worker = null; state.complete() }
                Thread.interrupted()
            }
            return
        }
        request.setAttribute(MANAGED, true)
        val state = State(request, response)
        request.setAttribute(STATE, state)
        state.async = request.startAsync(request, response).apply { timeout = 0 }
        state.alarm = timer.schedule({
            synchronized(state.gate) {
                if (!state.finished) { state.finish(503); state.worker?.interrupt() }
            }
        }, (state.end - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        if (request.contentLengthLong > MAX_BYTES) { state.finish(413); return }
        val input = request.inputStream
        val body = ByteArrayOutputStream()
        input.setReadListener(object : ReadListener {
            override fun onDataAvailable() = synchronized(state.gate) {
                if (state.finished) return@synchronized
                val buffer = ByteArray(8192)
                while (input.isReady && !input.isFinished) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (body.size() + n > MAX_BYTES) { state.finish(413); return@synchronized }
                    body.write(buffer, 0, n)
                }
            }
            override fun onAllDataRead() {
                synchronized(state.gate) {
                    if (!state.finished) { state.body = body.toByteArray(); state.async.dispatch() }
                }
            }
            override fun onError(failure: Throwable) { state.finish(503) }
        })
    }
    private inner class State(val request: HttpServletRequest, val response: HttpServletResponse) {
        val gate = Any()
        val start = System.nanoTime()
        val end = start + TimeUnit.SECONDS.toNanos(8)
        val id = request.getAttribute(RequestIdFilter.ATTRIBUTE).toString()
        val method = request.method
        var body = ByteArray(0)
        var finished = false
        var active = false
        var completed = false
        var worker: Thread? = null
        lateinit var async: jakarta.servlet.AsyncContext
        var alarm: ScheduledFuture<*>? = null
        val cached = GuardedResponse(response, gate, { finished }) { status, target -> problems.write(status, request, target) }
        fun finish(failure: Int? = null) {
            synchronized(gate) {
                if (finished) return
                finished = true
                alarm?.cancel(false)
                try {
                    if (failure != null) {
                        val bytes = mapper.writeValueAsBytes(problems.problem(failure, request))
                        response.resetBuffer()
                        response.status = failure
                        response.contentType = "application/problem+json"
                        response.setHeader("Cache-Control", "no-store")
                        response.setContentLength(bytes.size)
                        response.outputStream.write(bytes)
                    } else cached.copyBodyToResponse()
                    response.flushBuffer()
                } finally {
                    log.info("request id={} method={} status={} durationMs={}", id, method,
                        response.status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                    // A timed-out active dispatcher retains its request until its chain has exited.
                    if (!active) complete()
                }
            }
        }
        fun complete() {
            if (!completed) { completed = true; async.complete() }
        }
    }
    @PreDestroy fun close() { timer.shutdownNow() }
    companion object {
        const val MANAGED = "calorica.deadlineManaged"
        private const val STATE = "calorica.deadlineState"
        const val MAX_BYTES = 1024 * 1024
    }
}
