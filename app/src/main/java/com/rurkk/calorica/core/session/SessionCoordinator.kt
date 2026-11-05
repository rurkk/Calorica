package com.rurkk.calorica.core.session

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.AuthRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface RefreshTokenStore {
  suspend fun read(): String?
  suspend fun write(token: String)
  suspend fun clear()
}

sealed interface SessionState {
  data object Restoring : SessionState
  data class SignedOut(val message: String? = null) : SessionState
  // Generation keys the entire authenticated UI, including its ViewModelStore.
  data class SignedIn(val account: Account, val generation: Long) : SessionState
}

/** Owns rotation, storage and the in-memory access token. Never retries a rotation. */
@Singleton
class SessionCoordinator @Inject constructor(
    private val auth: AuthRepository,
    private val storage: RefreshTokenStore,
    private val memory: InMemorySession,
) {
  private val lock = Mutex()
  private val mutableState = MutableStateFlow<SessionState>(SessionState.Restoring)
  val state = mutableState.asStateFlow()
  private var restored = false
  private var generation = 0L
  @Volatile private var tokenRevision = 0L
  private var accessExpiresAt: Instant? = null

  suspend fun restore() = lock.withLock {
    if (restored) return@withLock
    restored = true
    val refresh = try { storage.read() } catch (_: Exception) {
      clearLocked("Не удалось восстановить сессию. Войдите снова.")
      return@withLock
    }
    if (refresh == null) clearLocked() else rotateLocked(refresh)
  }

  suspend fun authenticate(credentials: Credentials, register: Boolean): ApiResult<Unit> = lock.withLock {
    restored = true
    val result = if (register) auth.register(credentials) else auth.login(credentials)
    when (result) {
      is ApiResult.Success -> installLocked(result.value, freshSession = true)
      is ApiResult.Failure -> result
    }
  }

  suspend fun logout(): ApiResult<Unit> = lock.withLock {
    // An idle app may have an expired access token: rotate once before requesting revocation.
    var confirmed = false
    try {
      if (mutableState.value is SessionState.SignedIn && accessExpiresAt?.isAfter(Instant.now().plusSeconds(5)) != true) {
        val refresh = try { storage.read() } catch (_: Exception) { null }
        val ready = if (refresh == null) unauthorized() else rotateLocked(refresh)
        if (ready is ApiResult.Failure) return@withLock ready
      }
      auth.logout().also { confirmed = it is ApiResult.Success }
    } finally {
      withContext(NonCancellable) { clearLocked(if (confirmed) null else
          "Вы вышли на этом устройстве. Сервер не подтвердил отзыв сессии.") }
    }
  }

  /** Refresh only before an operation. Queued callers re-check the current expiry under the lock. */
  suspend fun ensureAccess(): ApiResult<Unit> = lock.withLock { ensureAccessLocked() }

  private suspend fun ensureAccessLocked(): ApiResult<Unit> {
    if (mutableState.value !is SessionState.SignedIn) return unauthorized()
    if (accessExpiresAt?.isAfter(Instant.now().plusSeconds(5)) == true) return ApiResult.Success(Unit)
    val refresh = try { storage.read() } catch (_: Exception) { null }
    return if (refresh == null) {
      clearLocked("Сессия завершена. Войдите снова.")
      unauthorized()
    } else rotateLocked(refresh)
  }

  /** No protected operation, including a write, is repeated automatically after a response. */
  suspend fun <T> authorized(operation: suspend () -> ApiResult<T>): ApiResult<T> {
    // Snapshot BEFORE the first suspension. A caller queued behind a new login cannot
    // acquire that new account's identity when it eventually obtains the mutex.
    val expectedGeneration = (state.value as? SessionState.SignedIn)?.generation ?: return unauthorized()
    var requestRevision = -1L
    val snapshot: ApiResult<SessionRequestBinding> = lock.withLock {
      if ((state.value as? SessionState.SignedIn)?.generation != expectedGeneration) return@withLock unauthorized()
      val ready = ensureAccessLocked()
      if (ready is ApiResult.Failure) return@withLock ready
      if ((state.value as? SessionState.SignedIn)?.generation != expectedGeneration) return@withLock unauthorized()
      val access = memory.accessToken() ?: return@withLock unauthorized()
      requestRevision = tokenRevision
      ApiResult.Success(SessionRequestBinding("Bearer $access"))
    }
    if (snapshot is ApiResult.Failure) return snapshot
    val binding = (snapshot as ApiResult.Success<SessionRequestBinding>).value
    val result = withContext(binding) { operation() }
    if (result is ApiResult.Failure && result.error.status == 401) lock.withLock {
      if (state.value is SessionState.SignedIn && tokenRevision == requestRevision) {
        clearLocked("Сессия отозвана или истекла. Войдите снова.")
      }
    }
    return result
  }

  private suspend fun rotateLocked(refresh: String): ApiResult<Unit> {
    // Remove the old credential BEFORE sending a rotating request. A process death between
    // server commit and persistence must never replay the previous refresh at the next launch.
    try { storage.clear() } catch (cancelled: CancellationException) {
      withContext(NonCancellable) { clearLocked("Восстановление прервано. Войдите снова.") }
      throw cancelled
    } catch (_: Exception) {
      clearLocked("Не удалось безопасно обновить сессию. Войдите снова.")
      return ApiResult.Failure(ApiError(ApiError.Kind.INVALID_RESPONSE))
    }
    // If this request was sent, cancellation is also an ambiguous rotation.
    val result = try { auth.refresh(Refresh(refresh)) } catch (cancelled: CancellationException) {
      withContext(NonCancellable) { clearLocked("Восстановление прервано. Войдите снова.") }
      throw cancelled
    }
    return when (result) {
      is ApiResult.Success -> installLocked(result.value)
      is ApiResult.Failure -> {
        clearLocked(if (result.error.status == 401) "Сессия завершена. Войдите снова."
            else "Не удалось безопасно восстановить сессию. Войдите снова.")
        result
      }
    }
  }

  private suspend fun installLocked(tokens: Tokens, freshSession: Boolean = false): ApiResult<Unit> {
    val expiry = runCatching { Instant.parse(tokens.accessExpiresAt) }.getOrNull()
    if (tokens.tokenType != "Bearer" || tokens.accessToken.isBlank() || tokens.refreshToken.isBlank() || expiry == null) {
      clearLocked("Некорректный ответ сервера. Войдите снова.")
      return ApiResult.Failure(ApiError(ApiError.Kind.INVALID_RESPONSE))
    }
    try { storage.write(tokens.refreshToken) } catch (cancelled: CancellationException) {
      withContext(NonCancellable) { clearLocked() }; throw cancelled
    } catch (_: Exception) {
      clearLocked("Не удалось защитить данные сессии. Войдите снова.")
      return ApiResult.Failure(ApiError(ApiError.Kind.INVALID_RESPONSE))
    }
    memory.replaceAccessToken(tokens.accessToken)
    accessExpiresAt = expiry
    // Rotation preserves the current user's UI. A fresh login gets a fresh generation.
    if (freshSession || mutableState.value !is SessionState.SignedIn) generation++
    tokenRevision++
    mutableState.value = SessionState.SignedIn(tokens.account, generation)
    return ApiResult.Success(Unit)
  }

  private suspend fun clearLocked(message: String? = null) {
    memory.clear()
    accessExpiresAt = null
    generation++
    tokenRevision++
    withContext(NonCancellable) {
      val cleared = runCatching { storage.clear() }.isSuccess
      mutableState.value = SessionState.SignedOut(if (cleared) message else "Не удалось очистить защищённое хранилище. Повторите выход перед закрытием приложения.")
    }
  }

  private fun unauthorized() = ApiResult.Failure(ApiError(ApiError.Kind.HTTP, 401, "UNAUTHORIZED"))
}
