package ru.calorica.accounts

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.calorica.accounts.application.PasswordHashing
import ru.calorica.accounts.domain.CredentialPolicy
import ru.calorica.accounts.domain.Credentials
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.security.AuthRateLimiter
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(var now: Instant = Instant.parse("2026-11-01T00:00:00Z")) : Clock() {
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

class AuthPolicyTest {
    private val policy = CredentialPolicy(Validation.buildDefaultValidatorFactory().validator)
    @Test fun `email is normalized and password length counts Unicode codepoints without trimming`() {
        assertThat(policy.normalize(Credentials(" Person@Example.org ", "😀".repeat(8)))).isEqualTo("person@example.org")
        assertThat(policy.normalize(Credentials("a@example.org", " " .repeat(8)))).isEqualTo("a@example.org")
        for (password in listOf("short", "😀".repeat(129)))
            assertThatThrownBy { policy.normalize(Credentials("a@example.org", password)) }.isInstanceOf(ApiFailure::class.java)
        for (email in listOf("", " ", "wrong", "x@", "a@b@c.org", "a".repeat(255) + "@b.org"))
            assertThatThrownBy { policy.normalize(Credentials(email, "password8")) }.isInstanceOf(ApiFailure::class.java)
    }
    @Test fun `Argon2id uses independent salt and OWASP minimum costs`() {
        val passwords = PasswordHashing()
        val first = passwords.encode(" correct horse ")
        val second = passwords.encode(" correct horse ")
        assertThat(first).startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$")
        assertThat(first).isNotEqualTo(second).doesNotContain("correct horse")
        assertThat(passwords.matches(" correct horse ", first)).isTrue()
        assertThat(passwords.matches("correct horse", first)).isFalse()
        assertThat(passwords.matches("anything8", null)).isFalse()
    }
    @Test fun `register and login share a bucket refresh is separate and spoofing cannot evict it`() {
        val clock = MutableClock()
        val limits = AuthRateLimiter(clock)
        repeat(10) { assertThat(limits.retryAfter("ip", false)).isNull() }
        assertThat(limits.retryAfter("ip", false)).isEqualTo(60)
        repeat(30) { assertThat(limits.retryAfter("ip", true)).isNull() }
        assertThat(limits.retryAfter("ip", true)).isEqualTo(60)
        assertThat(limits.retryAfter("other-ip", false)).isNull()
        clock.now = clock.now.plusSeconds(59)
        assertThat(limits.retryAfter("ip", false)).isEqualTo(1)
        clock.now = clock.now.plusSeconds(1)
        assertThat(limits.retryAfter("ip", false)).isNull()
    }
}
