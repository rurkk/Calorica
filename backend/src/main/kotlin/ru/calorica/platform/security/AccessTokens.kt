package ru.calorica.platform.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.*
import org.springframework.stereotype.Component
import ru.calorica.platform.api.ApiFailure
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@Component
class AccessTokens(
    private val clock: Clock,
    @Value("\${calorica.auth.jwt-secret:}") secret: String,
) {
    private val key = SecretKeySpec(if (secret.isBlank()) {
        // Single-instance development/teaching stand: restart invalidates access only, not refresh sessions.
        LoggerFactory.getLogger(javaClass).info("Using a process-local JWT key; existing sessions can refresh after restart")
        ByteArray(32).also { SecureRandom().nextBytes(it) }
    } else Base64.getDecoder().decode(secret).also { require(it.size >= 32) { "JWT key must contain at least 256 bits" } }, "HmacSHA256")
    private val encoder = NimbusJwtEncoder(ImmutableSecret<com.nimbusds.jose.proc.SecurityContext>(key))
    private val decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
        val timestamps = JwtTimestampValidator(Duration.ZERO).also { it.setClock(clock) }
        val claims = OAuth2TokenValidator<Jwt> { jwt ->
            val now = clock.instant()
            val valid = jwt.audience == listOf(AUDIENCE) && jwt.expiresAt != null && jwt.issuedAt != null &&
                !jwt.issuedAt!!.isAfter(now) && jwt.expiresAt!!.isAfter(now) &&
                jwt.expiresAt!!.isAfter(jwt.issuedAt) &&
                Duration.between(jwt.issuedAt, jwt.expiresAt) <= Duration.ofMinutes(15) &&
                runCatching { UUID.fromString(jwt.subject); UUID.fromString(jwt.getClaimAsString("sid")) }.isSuccess
            if (valid) OAuth2TokenValidatorResult.success()
            else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
        }
        setJwtValidator(DelegatingOAuth2TokenValidator(timestamps, JwtIssuerValidator(ISSUER), claims))
    }
    data class Issued(val value: String, val expiresAt: Instant)
    data class Identity(val accountId: UUID, val sessionId: UUID)
    fun issue(accountId: UUID, sessionId: UUID, sessionExpiresAt: Instant): Issued {
        val now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val expires = minOf(now.plusSeconds(900), sessionExpiresAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        val claims = JwtClaimsSet.builder().issuer(ISSUER).audience(listOf(AUDIENCE)).subject(accountId.toString())
            .issuedAt(now).expiresAt(expires).claim("sid", sessionId.toString()).build()
        val jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        return Issued(jwt.tokenValue, expires)
    }
    fun verify(token: String): Identity {
        if (token.length > 4096) throw ApiFailure.unauthorized()
        return try {
            val jwt = decoder.decode(token)
            Identity(UUID.fromString(jwt.subject), UUID.fromString(jwt.getClaimAsString("sid")))
        } catch (_: JwtException) { throw ApiFailure.unauthorized() }
          catch (_: IllegalArgumentException) { throw ApiFailure.unauthorized() }
    }
    companion object {
        const val ISSUER = "https://calorica-backend.onrender.com"
        const val AUDIENCE = "calorica-api"
    }
}
