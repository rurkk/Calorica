package com.rurkk.calorica

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.AuthRepository
import com.rurkk.calorica.core.session.*
import com.rurkk.calorica.features.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {
  private val account = Account("00000000-0000-4000-8000-000000000001", "one@example.test", "USER", Profile(null,null,null,null))
  private fun tokens(refresh: String = "rotated", expiry: String = "2099-01-01T00:00:00Z") =
      Tokens("access-$refresh", refresh, "Bearer", expiry, "2099-02-01T00:00:00Z", account)
  private class Store(var value: String? = null) : RefreshTokenStore {
    var writes = 0
    var failWrite = false
    override suspend fun read() = value
    override suspend fun write(token: String) { if (failWrite) error("storage unavailable"); value = token; writes++ }
    override suspend fun clear() { value = null }
  }
  private inner class Auth : AuthRepository {
    var calls = 0
    val refreshArguments = mutableListOf<String>()
    var loginCalls = 0
    var logoutCalls = 0
    var refreshResult: ApiResult<Tokens> = ApiResult.Success(tokens())
    var loginResult: ApiResult<Tokens> = ApiResult.Success(tokens("login"))
    var logoutResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun register(request: Credentials) = login(request)
    override suspend fun login(request: Credentials): ApiResult<Tokens> { loginCalls++; gate?.await(); return loginResult }
    override suspend fun refresh(request: Refresh): ApiResult<Tokens> {
      calls++; refreshArguments += request.refreshToken; gate?.await(); return refreshResult
    }
    override suspend fun logout(): ApiResult<Unit> { logoutCalls++; return logoutResult }
    override suspend fun me() = ApiResult.Success(account)
  }
  @Test fun concurrentRestoreRotatesOnceAndPublishesOnlyAfterPersistence() = runTest {
    val auth = Auth().apply { gate = CompletableDeferred() }
    val store = Store("old")
    val memory = InMemorySession()
    val session = SessionCoordinator(auth, store, memory)
    val first = async { session.restore() }
    val second = async { session.restore() }
    runCurrent()
    assertEquals(1, auth.calls)
    assertEquals(SessionState.Restoring, session.state.value)
    // Crash-safe: the previous token is already gone while the HTTP request is in flight.
    assertNull(store.value)
    assertNull(memory.accessToken())
    auth.gate!!.complete(Unit)
    first.await(); second.await()
    assertEquals(1, store.writes)
    assertEquals("rotated", store.value)
    assertEquals("access-rotated", memory.accessToken())
    assertTrue(session.state.value is SessionState.SignedIn)
  }
  @Test fun queuedExpiredAccessCallersUseNewRotationRatherThanReplayingCapturedToken() = runTest {
    val auth = Auth().apply { loginResult = ApiResult.Success(tokens("old", "2000-01-01T00:00:00Z")) }
    val store = Store()
    val session = SessionCoordinator(auth, store, InMemorySession())
    session.authenticate(Credentials(account.email, "password"), false)
    auth.gate = CompletableDeferred()
    val one = async { session.ensureAccess() }
    val two = async { session.ensureAccess() }
    runCurrent(); assertEquals(listOf("old"), auth.refreshArguments)
    auth.gate!!.complete(Unit)
    assertTrue(one.await() is ApiResult.Success)
    assertTrue(two.await() is ApiResult.Success)
    assertEquals(listOf("old"), auth.refreshArguments)
    assertEquals("rotated", store.value)
  }
  @Test fun ambiguousRefreshForgetsTokenAndCannotRepeatIt() = runTest {
    val auth = Auth().apply { refreshResult = ApiResult.Failure(ApiError(ApiError.Kind.NETWORK)) }
    val store = Store("old")
    val memory = InMemorySession()
    val session = SessionCoordinator(auth, store, memory)
    session.restore(); session.restore(); session.ensureAccess()
    assertEquals(1, auth.calls)
    assertNull(store.value); assertNull(memory.accessToken())
    assertTrue(session.state.value is SessionState.SignedOut)
  }
  @Test fun cancelledRotationAlsoForgetsToken() = runTest {
    val auth = Auth().apply { gate = CompletableDeferred() }
    val store = Store("old")
    val session = SessionCoordinator(auth, store, InMemorySession())
    val job = launch { session.restore() }
    runCurrent(); job.cancelAndJoin()
    assertNull(store.value)
    assertTrue(session.state.value is SessionState.SignedOut)
    session.restore(); assertEquals(1, auth.calls)
  }
  @Test fun rejectedOrUnpersistedSessionNeverReachesAuthenticatedUi() = runTest {
    for (failure in listOf(false, true)) {
      val auth = Auth().apply { if (!failure) refreshResult = ApiResult.Failure(ApiError(ApiError.Kind.HTTP,401,"UNAUTHORIZED")) }
      val store = Store("old").apply { failWrite = failure }
      val memory = InMemorySession()
      val session = SessionCoordinator(auth,store,memory)
      session.restore()
      assertNull(memory.accessToken()); assertNull(store.value)
      assertTrue(session.state.value is SessionState.SignedOut)
    }
  }
  @Test fun offlineLogoutClearsLocalCredentialsAndNextLoginGetsFreshUiGeneration() = runTest {
    val auth = Auth()
    val store = Store()
    val memory = InMemorySession()
    val session = SessionCoordinator(auth,store,memory)
    session.authenticate(Credentials(account.email,"password"), false)
    val first = (session.state.value as SessionState.SignedIn).generation
    auth.logoutResult = ApiResult.Failure(ApiError(ApiError.Kind.NETWORK))
    session.logout()
    assertNull(store.value); assertNull(memory.accessToken())
    assertTrue(session.state.value is SessionState.SignedOut)
    session.authenticate(Credentials("two@example.test","password"), false)
    assertNotEquals(first,(session.state.value as SessionState.SignedIn).generation)
  }
  @Test fun logoutAfterIdleRefreshesBeforeRevokingAndStillClearsRotatedCredential() = runTest {
    val auth = Auth().apply { loginResult = ApiResult.Success(tokens("old", "2000-01-01T00:00:00Z")) }
    val store = Store()
    val session = SessionCoordinator(auth,store,InMemorySession())
    session.authenticate(Credentials(account.email,"password"),false)
    session.logout()
    assertEquals(listOf("old"),auth.refreshArguments)
    assertEquals(1,auth.logoutCalls)
    assertNull(store.value)
    assertEquals(SessionState.SignedOut(),session.state.value)
  }
  @Test fun protectedUnauthorizedInvalidatesWithoutRepeatingWrite() = runTest {
    val auth = Auth()
    val store = Store()
    val session = SessionCoordinator(auth,store,InMemorySession())
    session.authenticate(Credentials(account.email,"password"), false)
    var writes = 0
    session.authorized<Unit> { writes++; ApiResult.Failure(ApiError(ApiError.Kind.HTTP,401,"UNAUTHORIZED")) }
    assertEquals(1,writes); assertEquals(0,auth.calls)
    assertNull(store.value); assertTrue(session.state.value is SessionState.SignedOut)
  }
  @Test fun lateUnauthorizedFromPreviousAccountDoesNotClearNewSession() = runTest {
    val auth = Auth()
    val session = SessionCoordinator(auth,Store(),InMemorySession())
    session.authenticate(Credentials(account.email,"password"),false)
    val gate = CompletableDeferred<Unit>()
    val request = async { session.authorized<Unit> { gate.await(); ApiResult.Failure(ApiError(ApiError.Kind.HTTP,401,"UNAUTHORIZED")) } }
    runCurrent(); session.logout(); session.authenticate(Credentials("two@example.test","password"),false)
    val current = session.state.value
    gate.complete(Unit); request.await()
    assertEquals(current,session.state.value)
  }
  @Test fun operationQueuedBehindNewLoginCannotAcquireNewAccountIdentity() = runTest {
    val auth = Auth()
    val memory = InMemorySession()
    val session = SessionCoordinator(auth,Store(),memory)
    session.authenticate(Credentials(account.email,"password"),false)
    auth.loginResult = ApiResult.Success(tokens("account-b"))
    auth.gate = CompletableDeferred()
    val login = async { session.authenticate(Credentials("two@example.test","password"),false) }
    runCurrent()
    var writes = 0
    val oldRequest = async { session.authorized<Unit> { writes++; ApiResult.Success(Unit) } }
    runCurrent()
    auth.gate!!.complete(Unit)
    login.await()
    val result = oldRequest.await()
    assertEquals(0,writes)
    assertEquals(401,(result as ApiResult.Failure).error.status)
    assertEquals("access-account-b",memory.accessToken())
  }
  @Test fun formValidatesUnicodeWithoutTrimmingPasswordAndSuppressesDuplicateSubmit() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      assertNull(validateCredentials(" one@example.test ", "😀".repeat(8)).second)
      assertNotNull(validateCredentials("broken", "short").first)
      val auth = Auth().apply { gate = CompletableDeferred() }
      val session = SessionCoordinator(auth,Store(),InMemorySession())
      val vm = AuthViewModel(session)
      vm.email(account.email); vm.password("password"); vm.submit(); vm.submit()
      assertTrue(vm.form.value.busy)
      runCurrent(); assertEquals(1,auth.loginCalls)
      auth.gate!!.complete(Unit); runCurrent()
      assertFalse(vm.form.value.busy); assertEquals("", vm.form.value.password)
      assertTrue(vm.sessionState.value is SessionState.SignedIn)
    } finally { Dispatchers.resetMain() }
  }
  @Test fun wrongLoginStaysOnFormWithSafeErrorAndNoSavedPassword() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val auth = Auth().apply { loginResult = ApiResult.Failure(ApiError(ApiError.Kind.HTTP,401,"UNAUTHORIZED")) }
      val vm = AuthViewModel(SessionCoordinator(auth,Store(),InMemorySession()))
      vm.email(account.email); vm.password("password"); vm.submit(); runCurrent()
      assertEquals("Неверный email или пароль.",vm.form.value.message)
      assertEquals("",vm.form.value.password)
      assertFalse(vm.sessionState.value is SessionState.SignedIn)
    } finally { Dispatchers.resetMain() }
  }
}
