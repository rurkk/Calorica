package ru.calorica.platform.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/** Java preserves Servlet's nullable header-removal semantics despite Spring's non-null annotations. */
public final class GuardedResponse extends ContentCachingResponseWrapper {
    private final Object gate;
    private final BooleanSupplier finished;
    private final BiConsumer<Integer, HttpServletResponse> problem;
    public GuardedResponse(HttpServletResponse response, Object gate, BooleanSupplier finished,
                           BiConsumer<Integer, HttpServletResponse> problem) {
        super(response); this.gate = gate; this.finished = finished; this.problem = problem;
    }
    private void change(Runnable action) { synchronized (gate) { if (!finished.getAsBoolean()) action.run(); } }
    @Override public void setStatus(int status) { change(() -> super.setStatus(status)); }
    @Override public void setHeader(String name, String value) { change(() -> super.setHeader(name, value)); }
    @Override public void addHeader(String name, String value) { change(() -> super.addHeader(name, value)); }
    @Override public void setIntHeader(String name, int value) { change(() -> super.setIntHeader(name, value)); }
    @Override public void addIntHeader(String name, int value) { change(() -> super.addIntHeader(name, value)); }
    @Override public void setDateHeader(String name, long value) { change(() -> super.setDateHeader(name, value)); }
    @Override public void addDateHeader(String name, long value) { change(() -> super.addDateHeader(name, value)); }
    @Override public void addCookie(Cookie cookie) { change(() -> super.addCookie(cookie)); }
    @Override public void setContentType(String type) { change(() -> super.setContentType(type)); }
    @Override public void setCharacterEncoding(String charset) { change(() -> super.setCharacterEncoding(charset)); }
    @Override public void setLocale(Locale locale) { change(() -> super.setLocale(locale)); }
    @Override public void setBufferSize(int size) { change(() -> super.setBufferSize(size)); }
    @Override public void reset() { change(() -> super.reset()); }
    @Override public void resetBuffer() { change(() -> super.resetBuffer()); }
    @Override public void sendError(int status) { change(() -> problem.accept(status, this)); }
    @Override public void sendError(int status, String message) { sendError(status); }
    @Override public void sendRedirect(String location) { change(() -> { super.setStatus(302); super.setHeader("Location", location); }); }
}
