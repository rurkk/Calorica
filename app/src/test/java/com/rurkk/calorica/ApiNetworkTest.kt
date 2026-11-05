package com.rurkk.calorica

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.*
import com.rurkk.calorica.core.session.*
import com.rurkk.calorica.core.ui.ServerWarmupController
import com.rurkk.calorica.core.ui.ServerWarmupState
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    api = ApiClient.create(server.url("/").toString(), ApiClient.http(session, server.url("/").toString()), allowHttp = true)
  }
  @After fun tearDown() { server.shutdown() }
  private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

  @Test fun realHttpReadUsesSharedSessionAndContractQuery() = runBlocking {
    session.replaceAccessToken("FAKE-NOT-A-TOKEN")
    server.enqueue(response("""{"items":[],"nextCursor":null}"""))
    val result = HttpProductRepository(api, executor, session).search("суп", 20, null)
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
  @Test fun sequentialActionsDoNotReuseIdleConnectionsWhenWriteRetriesAreDisabled() = runBlocking {
    // The server may silently expire its TLS keep-alive between UI actions. We must
    // start a fresh connection, not recover by replaying a possibly committed write.
    repeat(2) {
      server.enqueue(response("""{"status":"UP"}"""))
      assertTrue(executor.call { api.readiness() } is ApiResult.Success)
      assertEquals(0, server.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber)
    }
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    val result = executor.call {
      api.createDiary(DiaryWrite("id", java.math.BigDecimal.ONE, "g", "2026-11-01T00:00:00Z", emptyList()))
    }
    assertTrue(result is ApiResult.Failure)
    assertEquals(0, server.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber)
    assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    assertEquals(3, server.requestCount)
  }
  @Test fun accessTokenIsNotSentToAnonymousRoutesOrAnotherOrigin() = runBlocking {
    session.replaceAccessToken("FAKE-NOT-A-TOKEN")
    server.enqueue(response("""{"status":"UP"}"""))
    executor.call { api.readiness() }
    assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    server.enqueue(response("{}", 401))
    executor.call { api.login(Credentials("student@example.test", "FAKE-PASSWORD")) }
    assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    val other = MockWebServer()
    other.start()
    try {
      other.enqueue(response("""{"items":[],"nextCursor":null}"""))
      val foreign = ApiClient.create(other.url("/").toString(), ApiClient.http(session, server.url("/").toString()), true)
      executor.call { foreign.search(null, 20, null, "Bearer FAKE-NOT-A-TOKEN") }
      assertNull(other.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    } finally { other.shutdown() }
  }
  @Test fun oldAccountWriteKeepsCapturedAuthorizationAcrossLogoutLoginAndLateDispatch() = runBlocking {
    val account = Account("00000000-0000-4000-8000-000000000001","fake@example.test","USER",Profile(null,null,null,null))
    var next = Tokens("FAKE-ACCESS-A","FAKE-REFRESH-A","Bearer","2099-01-01T00:00:00Z","2099-02-01T00:00:00Z",account)
    val auth = object : AuthRepository {
      override suspend fun register(request: Credentials) = ApiResult.Success(next)
      override suspend fun login(request: Credentials) = ApiResult.Success(next)
      override suspend fun refresh(request: Refresh) = ApiResult.Success(next)
      override suspend fun logout() = ApiResult.Success(Unit)
      override suspend fun me() = ApiResult.Success(next.account)
    }
    val storage = object : RefreshTokenStore {
      var token:String? = null
      override suspend fun read() = token
      override suspend fun write(token:String) { this.token = token }
      override suspend fun clear() { token = null }
    }
    val coordinator = SessionCoordinator(auth,storage,session)
    coordinator.authenticate(Credentials("fake@example.test","FAKE-password"),false)
    val captured = CompletableDeferred<Unit>()
    val dispatch = CompletableDeferred<Unit>()
    server.enqueue(response("{}",401))
    val oldWrite = async {
      coordinator.authorized {
        captured.complete(Unit)
        dispatch.await()
        HttpProductRepository(api,executor,session).create(ProductCreate.Leaf("Old account write",
            NutritionInputSet(java.math.BigDecimal.ONE,java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO)))
      }
    }
    captured.await()
    coordinator.logout()
    next = next.copy(accessToken="FAKE-ACCESS-B",refreshToken="FAKE-REFRESH-B")
    coordinator.authenticate(Credentials("other@example.test","FAKE-password"),false)
    dispatch.complete(Unit)
    assertTrue(oldWrite.await() is ApiResult.Failure)
    val request = server.takeRequest(2,TimeUnit.SECONDS)!!
    assertEquals("Bearer FAKE-ACCESS-A",request.getHeader("Authorization"))
    assertEquals("FAKE-ACCESS-B",session.accessToken())
    assertTrue(coordinator.state.value is SessionState.SignedIn)
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
    val client = ApiClient.http(session, server.url("/").toString()).newBuilder().eventListener(object : EventListener() {
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
      assertThrows(IllegalArgumentException::class.java) { ApiClient.create(url, ApiClient.http(session, server.url("/").toString())) }
    }
  }

  @Test fun `startup retries only anonymous readiness GET until the server is ready`() = runBlocking {
    session.replaceAccessToken("FAKE-NOT-A-TOKEN")
    server.enqueue(response("<html>Starting</html>", 503))
    server.enqueue(response("<html>Starting</html>"))
    server.enqueue(response("""{"status":"UP"}"""))
    val warmup = ServerWarmupController(this) { HttpConnectionRepository(api, executor).check() }
    warmup.start()
    withTimeout(8_000) { warmup.state.first { it == ServerWarmupState.Ready } }
    assertEquals(3, server.requestCount)
    repeat(3) {
      val request = server.takeRequest(1, TimeUnit.SECONDS)!!
      assertEquals("GET", request.method)
      assertEquals("/actuator/health/readiness", request.path)
      assertNull(request.getHeader("Authorization"))
    }
  }

  @Test fun `pausing startup cancels the actual readiness HTTP request`() = runBlocking {
    val failed = CountDownLatch(1)
    val client = ApiClient.http(session, server.url("/").toString()).newBuilder().eventListener(object : EventListener() {
      override fun callFailed(call: Call, ioe: IOException) { if (call.isCanceled()) failed.countDown() }
    }).build()
    val cancellable = ApiClient.create(server.url("/").toString(), client, true)
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val warmup = ServerWarmupController(this) { HttpConnectionRepository(cancellable, executor).check() }
    warmup.start()
    withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
    warmup.pause()
    assertTrue(withContext(Dispatchers.IO) { failed.await(2, TimeUnit.SECONDS) })
    assertEquals(ServerWarmupState.Idle, warmup.state.value)
  }
}
