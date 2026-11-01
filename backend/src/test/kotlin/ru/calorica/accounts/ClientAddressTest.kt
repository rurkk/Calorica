package ru.calorica.accounts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.calorica.platform.security.AuthRateLimiter
import ru.calorica.platform.security.ClientAddress

class ClientAddressTest {
    @Test fun `only Render trusted peer can supply a chain and spoofed left prefix cannot change client`() {
        val local = ClientAddress(false)
        val render = ClientAddress(true)
        assertThat(local.resolve("10.1.2.3", "203.0.113.1")).isEqualTo("10.1.2.3")
        assertThat(render.resolve("198.51.100.8", "203.0.113.1")).isEqualTo("198.51.100.8")
        val first = render.resolve("10.1.2.3", "192.0.2.99, 203.0.113.1, 104.16.0.1")
        val second = render.resolve("10.1.2.3", "203.0.113.2, 104.16.0.1")
        assertThat(first).isEqualTo("203.0.113.1")
        assertThat(second).isEqualTo("203.0.113.2")
        val limiter = AuthRateLimiter(MutableClock())
        repeat(10) { assertThat(limiter.retryAfter(first,false)).isNull() }
        assertThat(limiter.retryAfter(first,false)).isEqualTo(60)
        assertThat(limiter.retryAfter(second,false)).isNull()
    }
    @Test fun `literal addresses are canonical and malformed overlong chains fail closed without DNS`() {
        val resolver = ClientAddress(true)
        val a = resolver.resolve("10.1.2.3", "2001:db8::1, 2606:4700::1111")
        val b = resolver.resolve("10.1.2.3", "2001:0db8:0000:0000:0000:0000:0000:0001, 2606:4700::1111")
        assertThat(a).isEqualTo(b)
        assertThat(resolver.resolve("10.1.2.3", "::ffff:203.0.113.1, 104.16.0.1")).isEqualTo("203.0.113.1")
        for (header in listOf("attacker.example.org", "203.0.113.1, nope", "999.999.999.999",
            List(33) { "203.0.113.1" }.joinToString(","), "a".repeat(4097)))
            assertThat(resolver.resolve("10.1.2.3",header)).isEqualTo("10.1.2.3")
    }
}
