package com.rurkk.calorica.features.products

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.ProductRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CatalogPhase { IDLE, LOADING, DATA, EMPTY, ERROR }
data class CatalogState(
    val query: String = "", val phase: CatalogPhase = CatalogPhase.IDLE,
    val items: List<Product> = emptyList(), val nextCursor: String? = null,
    val loadingMore: Boolean = false, val error: ApiError? = null,
)

/** A new search invalidates both the current page and all in-flight pagination. */
class ProductListController(private val scope: CoroutineScope, private val repository: ProductRepository) {
  private val mutableState = MutableStateFlow(CatalogState())
  val state = mutableState.asStateFlow()
  private var generation = 0L
  private var read: Job? = null
  fun search(query: String, debounce: Boolean = false): Job? {
    val request = ++generation
    read?.cancel()
    if (query.codePointCount(0,query.length) > 200) {
      mutableState.value = CatalogState(query, CatalogPhase.ERROR,
          error = ApiError(ApiError.Kind.HTTP, 422, "VALIDATION_FAILED"))
      return null
    }
    mutableState.value = CatalogState(query, CatalogPhase.LOADING)
    read = scope.launch {
      if (debounce) delay(300)
      val result = repository.search(query.takeIf { it.isNotBlank() }, 20, null)
      if (request != generation) return@launch
      mutableState.value = when (result) {
        is ApiResult.Success -> CatalogState(query,
            if (result.value.items.isEmpty()) CatalogPhase.EMPTY else CatalogPhase.DATA,
            result.value.items, result.value.nextCursor)
        is ApiResult.Failure -> CatalogState(query, CatalogPhase.ERROR, error = result.error)
      }
    }
    return read
  }
  fun reload(): Job? = search(state.value.query)
  fun nextPage() {
    val before = state.value
    val cursor = before.nextCursor ?: return
    if (before.phase != CatalogPhase.DATA || before.loadingMore || read?.isActive == true) return
    val request = generation
    mutableState.value = before.copy(loadingMore = true, error = null)
    read = scope.launch {
      val result = repository.search(before.query.takeIf { it.isNotBlank() }, 20, cursor)
      if (request != generation) return@launch
      mutableState.value = when (result) {
        is ApiResult.Success -> before.copy(
            items = (before.items + result.value.items).distinctBy { it.id },
            nextCursor = result.value.nextCursor, loadingMore = false, error = null)
        is ApiResult.Failure -> before.copy(loadingMore = false, error = result.error)
      }
    }
  }
  fun close() {
    generation++
    read?.cancel(); read = null
    mutableState.value = CatalogState()
  }
}
