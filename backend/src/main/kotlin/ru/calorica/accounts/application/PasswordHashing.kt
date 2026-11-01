package ru.calorica.accounts.application

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import ru.calorica.platform.api.ApiFailure
import java.util.concurrent.Semaphore

@Component
class PasswordHashing {
    // OWASP Argon2id minimum: m=19456 KiB, t=2, p=1. Bound concurrent heap/CPU use on Render Free.
    private val encoder = Argon2PasswordEncoder(16, 32, 1, 19456, 2)
    private val permit = Semaphore(1)
    private val dummy = encoder.encode("not-an-account-password")
    fun encode(password: String): String = bounded { encoder.encode(password) }
    fun matches(password: String, hash: String?): Boolean = bounded {
        val valid = encoder.matches(password, hash ?: dummy)
        hash != null && valid
    }
    private fun <T> bounded(action: () -> T): T {
        if (!permit.tryAcquire()) throw ApiFailure(429, "TOO_MANY_REQUESTS")
        try { return action() } finally { permit.release() }
    }
}
