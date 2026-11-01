package ru.calorica.accounts.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.calorica.platform.security.Actor
import ru.calorica.platform.security.Role
import java.time.Instant
import java.util.UUID

@Repository
class AccountRepository(private val jdbc: JdbcClient) {
    data class Row(val id: UUID, val email: String, val role: Role, val passwordHash: String)
    data class Session(val id: UUID, val accountId: UUID, val expiresAt: Instant, val revoked: Boolean)
    fun byEmail(email: String): Row? = jdbc.sql("SELECT * FROM calorica.accounts WHERE email=:email")
        .param("email", email).query { rs, _ -> Row(rs.getObject("id", UUID::class.java), rs.getString("email"),
            Role.valueOf(rs.getString("role")), rs.getString("password_hash")) }.optional().orElse(null)
    fun byId(id: UUID): Row = jdbc.sql("SELECT * FROM calorica.accounts WHERE id=:id").param("id", id)
        .query { rs, _ -> Row(id, rs.getString("email"), Role.valueOf(rs.getString("role")), rs.getString("password_hash")) }.single()
    fun create(id: UUID, email: String, hash: String, now: Instant) {
        jdbc.sql("INSERT INTO calorica.accounts(id,email,password_hash,created_at) VALUES (:id,:email,:hash,:now)")
            .param("id", id).param("email", email).param("hash", hash).param("now", now.atOffset(java.time.ZoneOffset.UTC)).update()
    }
    fun createSession(id: UUID, accountId: UUID, now: Instant, expires: Instant) {
        jdbc.sql("INSERT INTO calorica.sessions(id,account_id,created_at,expires_at) VALUES (:id,:account,:now,:expires)")
            .param("id", id).param("account", accountId).param("now", now.atOffset(java.time.ZoneOffset.UTC))
            .param("expires", expires.atOffset(java.time.ZoneOffset.UTC)).update()
    }
    fun insertRefresh(hash: String, sessionId: UUID, now: Instant) {
        jdbc.sql("INSERT INTO calorica.refresh_tokens(token_hash,session_id,created_at) VALUES (:hash,:id,:now)")
            .param("hash", hash).param("id", sessionId).param("now", now.atOffset(java.time.ZoneOffset.UTC)).update()
    }
    fun sessionForHash(hash: String): UUID? = jdbc.sql("SELECT session_id FROM calorica.refresh_tokens WHERE token_hash=:hash")
        .param("hash", hash).query(UUID::class.java).optional().orElse(null)
    fun lockSession(id: UUID): Session = jdbc.sql("SELECT * FROM calorica.sessions WHERE id=:id FOR UPDATE")
        .param("id", id).query { rs, _ -> Session(id, rs.getObject("account_id", UUID::class.java),
            rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("revoked_at") != null) }.single()
    fun consume(hash: String, now: Instant): Boolean = jdbc.sql(
        "UPDATE calorica.refresh_tokens SET used_at=:now WHERE token_hash=:hash AND used_at IS NULL")
        .param("hash", hash).param("now", now.atOffset(java.time.ZoneOffset.UTC)).update() == 1
    fun revoke(id: UUID, now: Instant) {
        jdbc.sql("UPDATE calorica.sessions SET revoked_at=coalesce(revoked_at,:now) WHERE id=:id")
            .param("id", id).param("now", now.atOffset(java.time.ZoneOffset.UTC)).update()
    }
    fun activeActor(id: UUID, accountId: UUID, now: Instant): Actor? = jdbc.sql("""
        SELECT a.id,a.role FROM calorica.sessions s JOIN calorica.accounts a ON a.id=s.account_id
        WHERE s.id=:id AND a.id=:account AND s.revoked_at IS NULL AND s.expires_at>:now
        """).param("id", id).param("account", accountId).param("now", now.atOffset(java.time.ZoneOffset.UTC))
        .query { rs, _ -> Actor(accountId, Role.valueOf(rs.getString("role")), id) }.optional().orElse(null)
}
