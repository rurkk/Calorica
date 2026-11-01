package com.rurkk.calorica

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadControllerTest {
  @Test fun loadingEmptyErrorRetryAreDistinctAndDoubleTapDoesNotDuplicateReads() = runTest {
    val reader = ReadController<List<String>>(backgroundScope) { it.isEmpty() }
    var reads = 0
    val gate = CompletableDeferred<Unit>()
    reader.load { reads++; gate.await(); ApiResult.Success(emptyList()) }
    reader.load { reads++; ApiResult.Success(listOf("duplicate")) }
    assertEquals(ReadState.Loading, reader.state.value)
    runCurrent(); assertEquals(1, reads)
    gate.complete(Unit); runCurrent()
    assertTrue(reader.state.value is ReadState.Empty)
    reader.load { reads++; ApiResult.Failure(ApiError(ApiError.Kind.NETWORK)) }; runCurrent()
    assertTrue(reader.state.value is ReadState.Error)
    reader.load { reads++; ApiResult.Success(listOf("fresh")) }; runCurrent()
    assertEquals(ReadState.Data(listOf("fresh")), reader.state.value)
    assertEquals(3, reads)
  }
  @Test fun closeCancelsRequestAndPreventsLatePublication() = runTest {
    val reader = ReadController<List<String>>(backgroundScope) { it.isEmpty() }
    var cancelled = false
    reader.load {
      try { awaitCancellation() } finally { cancelled = true }
    }
    runCurrent(); reader.close(); runCurrent()
    assertTrue(cancelled)
    assertEquals(ReadState.Idle, reader.state.value)
    reader.load { ApiResult.Success(listOf("new")) }; runCurrent()
    assertEquals(ReadState.Data(listOf("new")), reader.state.value)
  }
  @Test fun staleNonCooperativeReadCannotReplaceNewScreenResult() = runTest {
    val reader = ReadController<List<String>>(backgroundScope) { it.isEmpty() }
    reader.load { withContext(NonCancellable) { delay(1000) }; ApiResult.Success(listOf("old")) }
    runCurrent(); reader.close()
    reader.load { ApiResult.Success(listOf("new")) }; runCurrent()
    advanceTimeBy(1001); runCurrent()
    assertEquals(ReadState.Data(listOf("new")), reader.state.value)
  }
}
