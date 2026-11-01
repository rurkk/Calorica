package ru.calorica.accounts

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.security.AccessTokens
import java.util.Base64
import java.util.Date
import java.util.UUID

class AccessTokensTest {
    private val clock = MutableClock()
    private val key = ByteArray(32) { 42 }
    private val tokens = AccessTokens(clock, Base64.getEncoder().encodeToString(key))
    private val user = UUID.randomUUID()
    private val session = UUID.randomUUID()
    @Test fun `access ends at exactly fifteen minutes and does not outlive the absolute session`() {
        val issued = tokens.issue(user, session, clock.now.plusSeconds(30 * 86400))
        assertThat(issued.expiresAt).isEqualTo(clock.now.plusSeconds(900))
        assertThat(tokens.verify(issued.value)).isEqualTo(AccessTokens.Identity(user, session))
        clock.now = clock.now.plusSeconds(899)
        assertThat(tokens.verify(issued.value).accountId).isEqualTo(user)
        clock.now = clock.now.plusSeconds(1)
        assertThatThrownBy { tokens.verify(issued.value) }.isInstanceOf(ApiFailure::class.java)
        val short = tokens.issue(user, session, clock.now.plusSeconds(20))
        assertThat(short.expiresAt).isEqualTo(clock.now.plusSeconds(20))
    }
    @Test fun `issuer audience signature algorithm session and issued time are validated`() {
        fun signed(issuer: String = AccessTokens.ISSUER, audience: String = AccessTokens.AUDIENCE,
                   sid: String? = session.toString(), future: Boolean = false,
                   algorithm: JWSAlgorithm = JWSAlgorithm.HS256, signingKey: ByteArray = key): String {
            val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(user.toString())
                .issueTime(Date.from(clock.now.plusSeconds(if (future) 10 else 0)))
                .expirationTime(Date.from(clock.now.plusSeconds(900)))
            if (sid != null) claims.claim("sid", sid)
            return SignedJWT(JWSHeader(algorithm), claims.build()).apply { sign(MACSigner(signingKey)) }.serialize()
        }
        for (invalid in listOf(signed(issuer = "wrong"), signed(audience = "wrong"), signed(sid = null),
            signed(sid = "bad"), signed(future = true), signed(signingKey = ByteArray(32) { 1 }),
            signed(algorithm = JWSAlgorithm.HS512, signingKey = ByteArray(64) { 42 }), "eyJhbGciOiJub25lIn0.e30."))
            assertThatThrownBy { tokens.verify(invalid) }.isInstanceOf(ApiFailure::class.java)
    }
}
