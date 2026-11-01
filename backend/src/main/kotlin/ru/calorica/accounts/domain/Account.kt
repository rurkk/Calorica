package ru.calorica.accounts.domain

import ru.calorica.platform.security.Role
import ru.calorica.profile.domain.Profile
import java.time.Instant
import java.util.UUID

data class Account(val id: UUID, val email: String, val role: Role, val profile: Profile)
class Credentials(val email: String, val password: String)
class Refresh(val refreshToken: String)
class Tokens(
    val accessToken: String, val refreshToken: String,
    val accessExpiresAt: Instant, val sessionExpiresAt: Instant, val account: Account,
) { val tokenType = "Bearer" }
