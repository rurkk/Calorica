package com.rurkk.calorica.core.data

import com.rurkk.calorica.core.api.*
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
  suspend fun day(date: String, zone: String): ApiResult<DiaryDay>
  suspend fun create(request: DiaryWrite): ApiResult<DiaryEntry>
  suspend fun patch(id: String, request: DiaryPatch): ApiResult<DiaryEntry>
  suspend fun delete(id: String): ApiResult<Unit>
}
interface ConnectionRepository { suspend fun check(): ApiResult<Readiness> }

@Singleton class HttpAuthRepository @Inject constructor(private val api: CaloricaApi, private val executor: ApiExecutor) : AuthRepository {
  private val refreshLock = Mutex()
  override suspend fun register(request: Credentials) = executor.call { api.register(request) }
  override suspend fun login(request: Credentials) = executor.call { api.login(request) }
  override suspend fun refresh(request: Refresh) = refreshLock.withLock { executor.call { api.refresh(request) } }
  override suspend fun logout() = executor.empty { api.logout() }
  override suspend fun me() = executor.call { api.me() }
}
@Singleton class HttpProductRepository @Inject constructor(private val api: CaloricaApi, private val executor: ApiExecutor) : ProductRepository {
  override suspend fun search(q: String?, limit: Int, cursor: String?) = executor.call { api.search(q, limit, cursor) }
  override suspend fun get(id: String) = executor.call { api.product(id) }
  override suspend fun create(request: ProductCreate) = executor.call { api.createProduct(request.json()) }
  override suspend fun patch(id: String, request: ProductPatch) = executor.call { api.patchProduct(id, request.json()) }
  override suspend fun writeComposition(id: String, request: CompositionWrite) = executor.call { api.composition(id, request.json()) }
  override suspend fun delete(id: String, mode: String) = executor.empty { api.deleteProduct(id, mode) }
}
@Singleton class HttpDiaryRepository @Inject constructor(private val api: CaloricaApi, private val executor: ApiExecutor) : DiaryRepository {
  override suspend fun day(date: String, zone: String) = executor.call { api.day(date, zone) }
  override suspend fun create(request: DiaryWrite) = executor.call { api.createDiary(request) }
  override suspend fun patch(id: String, request: DiaryPatch) = executor.call { api.patchDiary(id, request.json()) }
  override suspend fun delete(id: String) = executor.empty { api.deleteDiary(id) }
}
@Singleton class HttpConnectionRepository @Inject constructor(private val api: CaloricaApi, private val executor: ApiExecutor) : ConnectionRepository {
  override suspend fun check() = executor.call { api.readiness() }
}
