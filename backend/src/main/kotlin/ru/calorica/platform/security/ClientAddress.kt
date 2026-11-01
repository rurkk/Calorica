package ru.calorica.platform.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component
import java.net.InetAddress

@Component
class ClientAddress(@param:Value("\${RENDER:false}") private val render: Boolean) {
    fun resolve(request: HttpServletRequest): String = resolve(request.remoteAddr, request.getHeader("X-Forwarded-For"))
    fun resolve(peer: String, forwarded: String?): String {
        val socket = literal(peer) ?: return peer
        // Render public traffic reaches the process through its private network; local/direct traffic
        // never gains trust by supplying headers. The XFF chain is consumed from its trusted end.
        if (!render || PRIVATE.none { it.matches(socket) }) return socket
        val chain = forwarded?.takeIf { it.length <= 4096 }?.split(',')?.takeIf { it.size <= 32 } ?: return socket
        for (raw in chain.asReversed()) {
            val address = literal(raw.trim()) ?: return socket
            if (TRUSTED.none { it.matches(address) }) return address
        }
        return socket
    }
    private fun literal(value: String): String? {
        if (value.length !in 3..45 || !value.all { it in "0123456789abcdefABCDEF:." } ||
            (':' !in value && !Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(value))) return null
        if (':' !in value && value.split('.').any { it.toIntOrNull() !in 0..255 }) return null
        return runCatching { InetAddress.getByName(value).hostAddress }.getOrNull()
    }
    companion object {
        private val PRIVATE = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7").map(::IpAddressMatcher)
        // Cloudflare published lists, checked 2026-11-01; see accounts/README.md for maintenance sources.
        private val TRUSTED = PRIVATE + listOf(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22", "2400:cb00::/32",
            "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32",
            "2a06:98c0::/29", "2c0f:f248::/32").map(::IpAddressMatcher)
    }
}
