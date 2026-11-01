package com.rurkk.calorica

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApiNetworkTest {
  private lateinit var server: MockWebServer
  private lateinit var session: InMemorySession
  private lateinit var api: CaloricaApi
  private val executor = ApiExecutor()
  @Before fun setup() {
    server = MockWebServer(); server.start()
    session = InMemorySession()
    api = ApiClient.create(server.url("/").toString(), ApiClient.http(session), allowHttp = true)
  }
  @After fun tearDown() { server.shutdown() }
  private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

  @Test fun realHttpReadUsesSharedSessionAndContractQuery() = runBlocking {
    session.replaceAccessToken("FAKE-NOT-A-TOKEN")
    server.enqueue(response("""{"items":[],"nextCursor":null}"""))
    val result = HttpProductRepository(api, executor).search("суп", 20, null)
    assertTrue(result is ApiResult.Success && result.value.items.isEmpty())
    val request = server.takeRequest(2, TimeUnit.SECONDS)!!
    assertEquals("/api/v1/products", request.requestUrl!!.encodedPath)
    assertEquals("суп", request.requestUrl!!.queryParameter("q"))
    assertEquals("Bearer FAKE-NOT-A-TOKEN", request.getHeader("Authorization"))
    session.clear()
    server.enqueue(response("""{"status":"UP"}"""))
    assertTrue(executor.call { api.readiness() } is ApiResult.Success)
    assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
  }
  @Test fun problemCodesAreTranslatedAndUntrustedDetailIsNeverDisplayed() = runBlocking {
    server.enqueue(response("""{"type":"about:blank","title":"Invalid","status":422,"detail":"DO-NOT-DISPLAY","code":"INVALID_COMPOSITION","correlationId":"00000000-0000-4000-8000-000000000007","errors":[{"field":"components","code":"PERCENT_SUM"}]}""", 422))
    val error = (executor.call { api.search(null, 20, null) } as ApiResult.Failure).error
    assertEquals("Проверьте состав и сумму процентов.", error.message())
    assertEquals("Сумма процентов должна быть равна 100.", error.fieldMessage(error.fields.single()))
    assertFalse(error.message().contains("DO-NOT-DISPLAY"))
    assertEquals("00000000-0000-4000-8000-000000000007", error.correlationId)
    server.enqueue(response("<html>DO-NOT-DISPLAY</html>", 503))
    val fallback = (executor.call { api.readiness() } as ApiResult.Failure).error
    assertEquals(ApiError.Kind.HTTP, fallback.kind)
    assertFalse(fallback.message().contains("DO-NOT-DISPLAY"))
    assertTrue(fallback.writeMessage().contains("неизвестен"))
  }
  @Test fun rateLimitAndMalformedSuccessDoNotBecomeEmptyLists() = runBlocking {
    server.enqueue(response("bad", 429).setHeader("Retry-After", "12"))
    val limited = (executor.call { api.search(null, 20, null) } as ApiResult.Failure).error
    assertEquals(12L, limited.retryAfterSeconds)
    server.enqueue(response("{}"))
    assertEquals(ApiError.Kind.INVALID_RESPONSE, (executor.call { api.search(null, 20, null) } as ApiResult.Failure).error.kind)
    server.enqueue(response("""{"items":[],"nextCursor":null}"""))
    assertTrue(executor.call { api.search(null, 20, null) } is ApiResult.Success)
  }
  @Test fun noResponseEndsAtWholeRequestDeadline() = runBlocking {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val start = System.nanoTime()
    val error = (executor.call { api.readiness() } as ApiResult.Failure).error
    val seconds = (System.nanoTime() - start) / 1_000_000_000.0
    assertEquals(ApiError.Kind.TIMEOUT, error.kind)
    assertTrue("deadline was $seconds seconds", seconds in 9.0..13.0)
    assertEquals(1, server.requestCount)
  }
  @Test fun screenCancellationCancelsActualHttpCallAndRemainsCancellation() = runBlocking {
    val failed = CountDownLatch(1)
    val client = ApiClient.http(session).newBuilder().eventListener(object : EventListener() {
      override fun callFailed(call: Call, ioe: IOException) { if (call.isCanceled()) failed.countDown() }
    }).build()
    val cancellable = ApiClient.create(server.url("/").toString(), client, true)
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val request = async { executor.call { cancellable.readiness() } }
    withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
    request.cancelAndJoin()
    assertTrue(request.isCancelled)
    assertTrue(withContext(Dispatchers.IO) { failed.await(2, TimeUnit.SECONDS) })
  }
  @Test fun outerTimeoutIsNotConvertedIntoNetworkError() = runBlocking {
    assertThrows(TimeoutCancellationException::class.java) {
      runBlocking { withTimeout(30) { executor.call<Readiness> { delay(1000); error("unreachable") } } }
    }
    Unit
  }
  @Test fun ambiguousWriteIsNotAutomaticallyRetried() = runBlocking {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    val result = executor.call { api.createDiary(DiaryWrite("id", java.math.BigDecimal.ONE, "g", "2026-11-01T00:00:00Z", emptyList())) }
    assertTrue(result is ApiResult.Failure)
    assertTrue((result as ApiResult.Failure).error.writeMessage().contains("неизвестен"))
    assertEquals(1, server.requestCount)
  }
  @Test fun redirectCannotLeakSession() = runBlocking {
    session.replaceAccessToken("FAKE-NOT-A-TOKEN")
    server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://other.example.test/do-not-follow"))
    val result = executor.call { api.readiness() }
    assertEquals(307, (result as ApiResult.Failure).error.status)
    assertEquals(1, server.requestCount)
  }
  @Test fun malformedDecimalObjectsAndArraysBecomeInvalidResponse() = runBlocking {
    for (bad in listOf("{}", "[]", "0.1")) {
      server.enqueue(response("""{"date":"2026-11-01","zone":"UTC","entries":[],"total":{"calories":$bad,"protein":"0","fat":"0","carbs":"0"}}"""))
      val result = executor.call { api.day("2026-11-01", "UTC") }
      assertEquals(ApiError.Kind.INVALID_RESPONSE, (result as ApiResult.Failure).error.kind)
    }
  }
  @Test fun productionRejectsHttpCredentialAndPathOverrides() {
    for (url in listOf("http://example.test/", "https://user:password@example.test/", "https://example.test/prefix/")) {
      assertThrows(IllegalArgumentException::class.java) { ApiClient.create(url, ApiClient.http(session)) }
    }
  }
}
