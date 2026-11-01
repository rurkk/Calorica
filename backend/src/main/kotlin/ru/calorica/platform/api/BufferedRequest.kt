package ru.calorica.platform.api

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** The container reads the bounded body non-blockingly before an application worker is allocated. */
class BufferedRequest(request: HttpServletRequest, bytes: ByteArray) : HttpServletRequestWrapper(request) {
    private val input = ByteArrayInputStream(bytes)
    override fun getInputStream() = object : ServletInputStream() {
        override fun read() = input.read()
        override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
        override fun isFinished() = input.available() == 0
        override fun isReady() = true
        override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException()
    }
    override fun getReader() = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
}
