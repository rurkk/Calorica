package com.rurkk.calorica.features.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.ProductRepository
import com.rurkk.calorica.core.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ProductMutation {
  data object Idle : ProductMutation
  data object Busy : ProductMutation
  data object Rechecking : ProductMutation
  data class Failure(val error: ApiError, val uncertain: Boolean) : ProductMutation
  data class Notice(val message: String) : ProductMutation
}

class ProductsViewModel(private val repository: ProductRepository, val accountId: String) : ViewModel() {
  private val listController = ProductListController(viewModelScope,repository)
  val catalog = listController.state
  private val detailController = ReadController<Product>(viewModelScope) { false }
  val detail = detailController.state
  private val mutableDraft = MutableStateFlow<ProductDraft?>(null)
  val draft = mutableDraft.asStateFlow()
  private val mutableMutation = MutableStateFlow<ProductMutation>(ProductMutation.Idle)
  val mutation = mutableMutation.asStateFlow()
  private var detailId: String? = null
  val canWrite get() = mutation.value != ProductMutation.Busy && mutation.value != ProductMutation.Rechecking &&
      (mutation.value as? ProductMutation.Failure)?.uncertain != true

  fun enter() { if ((mutation.value as? ProductMutation.Failure)?.uncertain == true) checkAfterFailure() else listController.reload() }
  fun search(query: String) { listController.search(query,debounce=true) }
  fun reload() { listController.reload() }
  fun nextPage() { listController.nextPage() }
  fun open(id: String) {
    if (!canWrite) return
    mutableDraft.value = null
    detailId = id
    detailController.close()
    detailController.load { repository.get(id) }
  }
  fun newProduct() {
    if (!canWrite) return
    mutableMutation.value = ProductMutation.Idle
    detailController.close(); detailId = null
    mutableDraft.value = ProductDraft()
  }
  fun edit(product: Product) {
    if (!canWrite || !product.canEditLeaf(accountId)) return
    mutableMutation.value = ProductMutation.Idle
    mutableDraft.value = ProductDraft.from(product)
  }
  fun change(field: String, value: String) {
    if (!canWrite) return
    val before = draft.value ?: return
    mutableDraft.value = when (field) {
      "name" -> before.copy(name=value,errors=emptyMap())
      "calories" -> before.copy(calories=value,errors=emptyMap())
      "protein" -> before.copy(protein=value,errors=emptyMap())
      "fat" -> before.copy(fat=value,errors=emptyMap())
      "carbs" -> before.copy(carbs=value,errors=emptyMap())
      else -> before
    }
  }
  fun save() {
    if (!canWrite) return
    val input = draft.value ?: return
    if (input.original != null && !input.original.canEditLeaf(accountId)) return
    val errors = input.validationErrors()
    if (errors.isNotEmpty()) { mutableDraft.value = input.copy(errors=errors); return }
    mutableMutation.value = ProductMutation.Busy
    viewModelScope.launch {
      val result = if (input.original == null) repository.create(ProductCreate.Leaf(input.name.trim(),input.nutrition()))
          else repository.patch(input.original.id,input.patch())
      when (result) {
        is ApiResult.Success -> {
          mutableMutation.value = ProductMutation.Idle
          open(result.value.id)
          listController.reload()
          mutableMutation.value = ProductMutation.Notice("Продукт сохранён.")
        }
        is ApiResult.Failure -> failed(result.error)
      }
    }
  }
  fun delete(product: Product) {
    if (!canWrite || !product.canDelete(accountId)) return
    mutableMutation.value = ProductMutation.Busy
    viewModelScope.launch {
      when (val result = repository.delete(product.id,"KEEP_REFERENCES")) {
        is ApiResult.Success -> {
          mutableDraft.value = null; detailController.close(); detailId = null
          listController.reload()
          mutableMutation.value = ProductMutation.Notice("Продукт удалён из каталога. История и существующие составы сохранены.")
        }
        is ApiResult.Failure -> failed(result.error)
      }
    }
  }
  private fun failed(error: ApiError) {
    mutableMutation.value = ProductMutation.Failure(error,
        error.kind != ApiError.Kind.HTTP || error.status == null || error.status >= 500)
  }
  fun checkAfterFailure() {
    if (mutation.value == ProductMutation.Busy || mutation.value == ProductMutation.Rechecking) return
    val input = draft.value
    val id = input?.original?.id ?: detailId
    mutableMutation.value = ProductMutation.Rechecking
    viewModelScope.launch {
      if (id != null) {
        when (val result = repository.get(id)) {
          is ApiResult.Success -> {
            mutableDraft.value = null
            detailId = id
            detailController.close(); detailController.load { result }
            listController.reload()
            mutableMutation.value = ProductMutation.Notice("Данные перечитаны с сервера.")
          }
          is ApiResult.Failure -> mutableMutation.value = ProductMutation.Failure(result.error,true)
        }
      } else {
        listController.search(input?.name?.trim().orEmpty())?.join()
        if (catalog.value.phase == CatalogPhase.DATA || catalog.value.phase == CatalogPhase.EMPTY) {
          mutableDraft.value = null; detailController.close(); detailId = null
          mutableMutation.value = ProductMutation.Notice("Проверьте каталог: результат предыдущего сохранения мог уже появиться.")
        } else mutableMutation.value = ProductMutation.Failure(catalog.value.error ?: ApiError(ApiError.Kind.NETWORK),true)
      }
    }
  }
  fun back() {
    if (mutation.value == ProductMutation.Busy || mutation.value == ProductMutation.Rechecking) return
    if ((mutation.value as? ProductMutation.Failure)?.uncertain == true) { checkAfterFailure(); return }
    mutableDraft.value = null
    detailId = null; detailController.close()
    mutableMutation.value = ProductMutation.Idle
    listController.reload()
  }
  fun retryDetail() { detailId?.let(::open) }
  fun leave() { listController.close(); detailController.close() }
  class Factory(private val repository: ProductRepository, private val accountId: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
      require(modelClass == ProductsViewModel::class)
      @Suppress("UNCHECKED_CAST") return ProductsViewModel(repository,accountId) as T
    }
  }
}
