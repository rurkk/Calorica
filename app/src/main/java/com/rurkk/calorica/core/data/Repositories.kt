package com.rurkk.calorica.core.data

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.session.requestAuthorization
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AuthRepository {
  suspend fun register(request: Credentials): ApiResult<Tokens>
  suspend fun login(request: Credentials): ApiResult<Tokens>
  suspend fun refresh(request: Refresh): ApiResult<Tokens>
  suspend fun logout(): ApiResult<Unit>
  suspend fun me(): ApiResult<Account>
}
interface ProductRepository {
  suspend fun search(q: String? = null, limit: Int = 20, cursor: String? = null): ApiResult<ProductPage>
  suspend fun get(id: String): ApiResult<Product>
  suspend fun create(request: ProductCreate): ApiResult<Product>
  suspend fun patch(id: String, request: ProductPatch): ApiResult<Product>
  suspend fun writeComposition(id: String, request: CompositionWrite): ApiResult<Product>
  suspend fun delete(id: String, mode: String = "KEEP_REFERENCES"): ApiResult<Unit>
}
interface DiaryRepository {
  suspend fun mealLabels(): ApiResult<List<MealLabel>>
  suspend fun day(date: String, zone: String): ApiResult<DiaryDay>
  suspend fun create(request: DiaryWrite): ApiResult<DiaryEntry>
  suspend fun patch(id: String, request: DiaryPatch): ApiResult<DiaryEntry>
  suspend fun delete(id: String): ApiResult<Unit>
}
interface ConnectionRepository { suspend fun check(): ApiResult<Readiness> }

@Singleton class HttpAuthRepository @Inject constructor(private val api: CaloricaApi, private val executor: ApiExecutor, private val memory: InMemorySession) : AuthRepository {
  private val refreshLock = Mutex()
  override suspend fun register(request: Credentials) = executor.call { api.register(request) }
  override suspend fun login(request: Credentials) = executor.call { api.login(request) }
  override suspend fun refresh(request: Refresh) = refreshLock.withLock { executor.call { api.refresh(request) } }
  override suspend fun logout() = requestAuthorization(memory).let { header -> executor.empty { api.logout(header) } }
  override suspend fun me() = requestAuthorization(memory).let { header -> executor.call { api.me(header) } }
}
@Singleton class HttpProductRepository @Inject constructor(private val api: CaloricaApi, private val executor: ApiExecutor, private val memory: InMemorySession) : ProductRepository {
  override suspend fun search(q: String?, limit: Int, cursor: String?) = requestAuthorization(memory).let { header -> executor.call { api.search(q, limit, cursor, header) } }
  override suspend fun get(id: String) = requestAuthorization(memory).let { header -> executor.call { api.product(id, header) } }
  override suspend fun create(request: ProductCreate) = requestAuthorization(memory).let { header -> executor.call { api.createProduct(request.json(), header) } }
  override suspend fun patch(id: String, request: ProductPatch) = requestAuthorization(memory).let { header -> executor.call { api.patchProduct(id, request.json(), header) } }
  override suspend fun writeComposition(id: String, request: CompositionWrite) = requestAuthorization(memory).let { header -> executor.call { api.composition(id, request.json(), header) } }
  override suspend fun delete(id: String, mode: String) = requestAuthorization(memory).let { header -> executor.empty { api.deleteProduct(id, mode, header) } }
}
@Singleton class HttpDiaryRepository @Inject constructor(private val api: CaloricaApi, private val executor: ApiExecutor, private val memory: InMemorySession) : DiaryRepository {
  override suspend fun mealLabels() = requestAuthorization(memory).let { header -> executor.call { api.mealLabels(header) } }
  override suspend fun day(date: String, zone: String) = requestAuthorization(memory).let { header -> executor.call { api.day(date, zone, header) } }
  override suspend fun create(request: DiaryWrite) = requestAuthorization(memory).let { header -> executor.call { api.createDiary(request, header) } }
  override suspend fun patch(id: String, request: DiaryPatch) = requestAuthorization(memory).let { header -> executor.call { api.patchDiary(id, request.json(), header) } }
  override suspend fun delete(id: String) = requestAuthorization(memory).let { header -> executor.empty { api.deleteDiary(id, header) } }
}
@Singleton class HttpConnectionRepository @Inject constructor(private val api: CaloricaApi, private val executor: ApiExecutor) : ConnectionRepository {
  override suspend fun check() = executor.call { api.readiness() }
}
