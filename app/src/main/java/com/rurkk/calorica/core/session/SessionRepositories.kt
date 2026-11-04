package com.rurkk.calorica.core.session

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class SessionProductRepository @Inject constructor(
    private val delegate: HttpProductRepository, private val session: SessionCoordinator,
) : ProductRepository {
  override suspend fun search(q: String?, limit: Int, cursor: String?) = session.authorized { delegate.search(q, limit, cursor) }
  override suspend fun get(id: String) = session.authorized { delegate.get(id) }
  override suspend fun create(request: ProductCreate) = session.authorized { delegate.create(request) }
  override suspend fun patch(id: String, request: ProductPatch) = session.authorized { delegate.patch(id, request) }
  override suspend fun writeComposition(id: String, request: CompositionWrite) = session.authorized { delegate.writeComposition(id, request) }
  override suspend fun delete(id: String, mode: String) = session.authorized { delegate.delete(id, mode) }
}
@Singleton class SessionDiaryRepository @Inject constructor(
    private val delegate: HttpDiaryRepository, private val session: SessionCoordinator,
) : DiaryRepository {
  override suspend fun mealLabels() = session.authorized { delegate.mealLabels() }
  override suspend fun day(date: String, zone: String) = session.authorized { delegate.day(date, zone) }
  override suspend fun create(request: DiaryWrite) = session.authorized { delegate.create(request) }
  override suspend fun patch(id: String, request: DiaryPatch) = session.authorized { delegate.patch(id, request) }
  override suspend fun delete(id: String) = session.authorized { delegate.delete(id) }
}
