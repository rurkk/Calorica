package com.rurkk.calorica.core.session

import com.rurkk.calorica.core.api.InMemorySession
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** Immutable identity for one operation, propagated across coroutine dispatcher switches. */
class SessionRequestBinding(val authorization: String) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<SessionRequestBinding>
  override fun toString() = "SessionRequestBinding([redacted])"
}

/** Captured before Retrofit creates/enqueues the request; OkHttp never reads mutable credentials. */
suspend fun requestAuthorization(memory: InMemorySession): String? =
    currentCoroutineContext()[SessionRequestBinding]?.authorization ?: memory.accessToken()?.let { "Bearer $it" }
