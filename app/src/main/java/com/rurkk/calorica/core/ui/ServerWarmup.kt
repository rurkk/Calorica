package com.rurkk.calorica.core.ui

import com.rurkk.calorica.core.api.ApiError
import com.rurkk.calorica.core.api.ApiResult
import com.rurkk.calorica.core.api.Readiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

const val SERVER_WARMUP_TIMEOUT_MILLIS = 90_000L
private const val PROBE_INTERVAL_MILLIS = 2_000L

sealed interface ServerWarmupState {
  data object Idle : ServerWarmupState
  data object Waiting : ServerWarmupState
  data object Ready : ServerWarmupState
  data class Failed(val error: ApiError?, val deadlineExceeded: Boolean) : ServerWarmupState
}

/** Foreground-only GET readiness retries. Business requests and their 10s deadline are unchanged. */
class ServerWarmupController(
    private val scope: CoroutineScope,
    private val check: suspend () -> ApiResult<Readiness>,
) {
  private val mutableState = MutableStateFlow<ServerWarmupState>(ServerWarmupState.Idle)
  val state: StateFlow<ServerWarmupState> = mutableState.asStateFlow()
  private var job: Job? = null
  private var generation = 0

  fun start() {
    if (mutableState.value != ServerWarmupState.Idle) return
    run()
  }

  fun retry() {
    if (mutableState.value !is ServerWarmupState.Failed) return
    run()
  }

  private fun run() {
    if (job?.isActive == true) return
    val request = ++generation
    mutableState.value = ServerWarmupState.Waiting
    job = scope.launch {
      var lastError: ApiError? = null
      val result = withTimeoutOrNull(SERVER_WARMUP_TIMEOUT_MILLIS) {
        while (true) {
          val response = check()
          currentCoroutineContext().ensureActive()
          when (response) {
            is ApiResult.Success -> {
              lastError = null
              if (response.value.status == "UP") return@withTimeoutOrNull ServerWarmupState.Ready
            }
            is ApiResult.Failure -> {
              lastError = response.error
              // Auth, a missing endpoint or a redirect cannot be repaired by waiting for startup.
              if (response.error.status in listOf(301, 302, 303, 307, 308, 400, 401, 403, 404)) {
                return@withTimeoutOrNull ServerWarmupState.Failed(response.error, false)
              }
            }
          }
          val retryAfter = lastError?.takeIf { it.status == 429 }?.retryAfterSeconds
          // Avoid overflow from an untrusted Retry-After; the outer deadline bounds the wait.
          delay(retryAfter?.coerceIn(2, 90)?.times(1_000) ?: PROBE_INTERVAL_MILLIS)
        }
        @Suppress("UNREACHABLE_CODE")
        ServerWarmupState.Ready
      } ?: ServerWarmupState.Failed(lastError, true)
      if (request == generation) mutableState.value = result
    }
  }

  fun pause() {
    if (mutableState.value != ServerWarmupState.Waiting) return
    generation++
    job?.cancel()
    job = null
    mutableState.value = ServerWarmupState.Idle
  }
}
