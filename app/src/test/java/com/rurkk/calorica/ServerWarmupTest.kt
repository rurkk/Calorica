package com.rurkk.calorica

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerWarmupTest {
  @Test fun `ready server opens immediately and is not polled again`() = runTest {
    var calls = 0
    val warmup = ServerWarmupController(backgroundScope) { calls++; ApiResult.Success(Readiness("UP")) }
    warmup.start(); warmup.start()
    assertEquals(ServerWarmupState.Waiting, warmup.state.value)
    runCurrent()
    assertEquals(ServerWarmupState.Ready, warmup.state.value)
    warmup.start(); warmup.retry(); warmup.pause()
    advanceTimeBy(100_000); runCurrent()
    assertEquals(1, calls)
    assertEquals(ServerWarmupState.Ready, warmup.state.value)
    assertEquals(10_000L, REQUEST_TIMEOUT_MILLIS)
  }

  @Test fun `cold server recovers after transient failures without repeating business requests`() = runTest {
    var calls = 0
    val warmup = ServerWarmupController(backgroundScope) {
      calls++
      when (calls) {
        1 -> ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT))
        2 -> ApiResult.Failure(ApiError(ApiError.Kind.INVALID_RESPONSE))
        3 -> ApiResult.Failure(ApiError(ApiError.Kind.HTTP, 503))
        4 -> ApiResult.Success(Readiness("DOWN"))
        else -> ApiResult.Success(Readiness("UP"))
      }
    }
    warmup.start(); runCurrent()
    advanceTimeBy(8_000); runCurrent()
    assertEquals(5, calls)
    assertEquals(ServerWarmupState.Ready, warmup.state.value)
  }

  @Test fun `outer deadline cancels an unfinished probe after ninety seconds`() = runTest {
    var cancelled = false
    val warmup = ServerWarmupController(backgroundScope) {
      try { awaitCancellation() } finally { cancelled = true }
    }
    warmup.start(); runCurrent()
    advanceTimeBy(90_000); runCurrent()
    assertTrue(cancelled)
    val failed = warmup.state.value as ServerWarmupState.Failed
    assertTrue(failed.deadlineExceeded)
  }

  @Test fun `continuous network failures finish at the deadline and stop polling`() = runTest {
    var calls = 0
    val warmup = ServerWarmupController(backgroundScope) {
      calls++; ApiResult.Failure(ApiError(ApiError.Kind.NETWORK))
    }
    warmup.start(); runCurrent()
    advanceTimeBy(90_000); runCurrent()
    assertTrue((warmup.state.value as ServerWarmupState.Failed).deadlineExceeded)
    val completedCalls = calls
    advanceTimeBy(90_000); runCurrent()
    assertEquals(completedCalls, calls)
  }

  @Test fun `retry after failure starts one new bounded attempt`() = runTest {
    var ready = false
    var calls = 0
    val warmup = ServerWarmupController(backgroundScope) {
      calls++
      if (ready) ApiResult.Success(Readiness("UP")) else ApiResult.Failure(ApiError(ApiError.Kind.NETWORK))
    }
    warmup.start(); runCurrent(); advanceTimeBy(90_000); runCurrent()
    ready = true
    val before = calls
    warmup.start() // Failed state requires an explicit user retry.
    assertTrue(warmup.state.value is ServerWarmupState.Failed)
    warmup.retry(); warmup.retry(); runCurrent()
    assertEquals(before + 1, calls)
    assertEquals(ServerWarmupState.Ready, warmup.state.value)
  }

  @Test fun `background pause cancels the request and foreground restarts it`() = runTest {
    var cancellations = 0
    val warmup = ServerWarmupController(backgroundScope) {
      try { awaitCancellation() } finally { cancellations++ }
    }
    warmup.start(); runCurrent(); warmup.pause(); runCurrent()
    assertEquals(1, cancellations)
    assertEquals(ServerWarmupState.Idle, warmup.state.value)
    advanceTimeBy(100_000); runCurrent()
    assertEquals(ServerWarmupState.Idle, warmup.state.value)
    warmup.start(); runCurrent(); warmup.pause(); runCurrent()
    assertEquals(2, cancellations)
  }

  @Test fun `late response from a paused attempt cannot replace the new state`() = runTest {
    var calls = 0
    val warmup = ServerWarmupController(backgroundScope) {
      if (++calls == 1) withContext(NonCancellable) { delay(10_000) }
      ApiResult.Success(Readiness("UP"))
    }
    warmup.start(); runCurrent(); warmup.pause()
    warmup.start(); runCurrent()
    assertEquals(ServerWarmupState.Ready, warmup.state.value)
    advanceTimeBy(10_000); runCurrent()
    assertEquals(ServerWarmupState.Ready, warmup.state.value)
    assertEquals(2, calls)
  }

  @Test fun `permanent configuration error finishes without waiting or repeating`() = runTest {
    var calls = 0
    val warmup = ServerWarmupController(backgroundScope) {
      calls++; ApiResult.Failure(ApiError(ApiError.Kind.HTTP, 404))
    }
    warmup.start(); runCurrent(); advanceTimeBy(90_000); runCurrent()
    assertEquals(1, calls)
    assertFalse((warmup.state.value as ServerWarmupState.Failed).deadlineExceeded)
  }

  @Test fun `rate limit backoff is bounded by the same overall deadline`() = runTest {
    var calls = 0
    val warmup = ServerWarmupController(backgroundScope) {
      calls++; ApiResult.Failure(ApiError(ApiError.Kind.HTTP, 429, retryAfterSeconds = Long.MAX_VALUE))
    }
    warmup.start(); runCurrent(); advanceTimeBy(90_000); runCurrent()
    assertTrue((warmup.state.value as ServerWarmupState.Failed).deadlineExceeded)
    assertEquals(1, calls)
  }
}
