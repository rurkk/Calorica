package ru.calorica.platform.security

import java.util.UUID

enum class Role { USER, ADMIN }

/** Current server-side identity. Consumers must use this principal, never parse a JWT themselves. */
data class Actor(val id: UUID, val role: Role, val sessionId: UUID)
