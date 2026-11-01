package ru.calorica.accounts.application

import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.calorica.accounts.domain.*
import ru.calorica.accounts.persistence.AccountRepository
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.security.AccessTokens
import ru.calorica.platform.security.Actor
import ru.calorica.profile.persistence.ProfileRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

@Service
class AccountsService(
    private val accounts: AccountRepository, private val profiles: ProfileRepository,
    private val policy: CredentialPolicy, private val passwords: PasswordHashing,
    private val access: AccessTokens, private val clock: Clock, manager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(manager).apply { timeout = 8; isolationLevel = org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED }
    private val readTx = TransactionTemplate(manager).apply {
        timeout = 8; isReadOnly = true
        isolationLevel = org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ
    }
    private val random = SecureRandom()
    fun register(credentials: Credentials): Tokens {
        val email = policy.normalize(credentials)
        val hash = passwords.encode(credentials.password)
        return try { tx.execute {
            val id = UUID.randomUUID()
            accounts.create(id, email, hash, clock.instant())
            profiles.create(id)
            newSession(id)
        }!! } catch (_: DuplicateKeyException) { throw ApiFailure(409, "EMAIL_CONFLICT") }
    }
    fun login(credentials: Credentials): Tokens {
        val email = policy.normalize(credentials)
        val row = accounts.byEmail(email)
        if (!passwords.matches(credentials.password, row?.passwordHash)) throw ApiFailure.unauthorized()
        return tx.execute { newSession(row!!.id) }!!
    }
    private fun newSession(id: UUID): Tokens {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val expires = now.plus(30, ChronoUnit.DAYS)
        val sessionId = UUID.randomUUID()
        accounts.createSession(sessionId, id, now, expires)
        val refresh = freshRefresh()
        accounts.insertRefresh(hash(refresh), sessionId, now)
        val issued = access.issue(id, sessionId, expires)
        return Tokens(issued.value, refresh, issued.expiresAt, expires, account(id))
    }
    fun refresh(raw: String): Tokens {
        if (raw.isEmpty() || raw.length > 4096) throw ApiFailure.invalid("refreshToken")
        val tokenHash = hash(raw)
        // A replay is returned as a value so revocation COMMITS before the caller receives 401.
        val result = tx.execute {
            val id = accounts.sessionForHash(tokenHash) ?: return@execute null
            val session = accounts.lockSession(id)
            val now = clock.instant()
            if (session.revoked || !session.expiresAt.isAfter(now)) return@execute null
            if (!accounts.consume(tokenHash, now)) {
                accounts.revoke(id, now)
                return@execute null
            }
            val next = freshRefresh()
            accounts.insertRefresh(hash(next), id, now)
            val issued = access.issue(session.accountId, id, session.expiresAt)
            Tokens(issued.value, next, issued.expiresAt, session.expiresAt, account(session.accountId))
        }
        return result ?: throw ApiFailure.unauthorized()
    }
    fun authenticate(token: String): Actor {
        val identity = access.verify(token)
        return accounts.activeActor(identity.sessionId, identity.accountId, clock.instant()) ?: throw ApiFailure.unauthorized()
    }
    fun me(actor: Actor): Account = readTx.execute { account(actor.id) }!!
    fun logout(actor: Actor) { tx.executeWithoutResult { accounts.revoke(actor.sessionId, clock.instant()) } }
    private fun account(id: UUID): Account {
        val row = accounts.byId(id)
        return Account(id, row.email, row.role, profiles.get(id))
    }
    private fun freshRefresh() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun hash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .let { HexFormat.of().formatHex(it) }
}
