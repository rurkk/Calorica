package com.rurkk.calorica.features.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.ProductRepository
import com.rurkk.calorica.core.navigation.*
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
  private val mutableComponentNames = MutableStateFlow<Map<String,String>>(emptyMap())
  val componentNames = mutableComponentNames.asStateFlow()
  private var detailGeneration = 0
  private val mutableComposition = MutableStateFlow<CompositionDraft?>(null)
  val composition = mutableComposition.asStateFlow()
  private val mutableResolvingComponent = MutableStateFlow(false)
  val resolvingComponent = mutableResolvingComponent.asStateFlow()
  private var componentRequest: ProductSelectionRequest? = null
  private var compositionGeneration = 0
  val canWrite get() = !resolvingComponent.value && mutation.value != ProductMutation.Busy && mutation.value != ProductMutation.Rechecking &&
      (mutation.value as? ProductMutation.Failure)?.uncertain != true

  fun enter() {
    if ((mutation.value as? ProductMutation.Failure)?.uncertain == true) checkAfterFailure()
    else if (detailId != null && draft.value == null && composition.value == null) retryDetail()
    else if (draft.value == null && composition.value == null) listController.reload()
  }
  fun search(query: String) { listController.search(query,debounce=true) }
  fun reload() { listController.reload() }
  fun nextPage() { listController.nextPage() }
  fun open(id: String) {
    if (!canWrite) return
    clearComposition()
    mutableDraft.value = null
    detailId = id
    detailController.close()
    val generation=++detailGeneration
    mutableComponentNames.value=emptyMap()
    detailController.load {
      repository.get(id).also { result ->
        if (result is ApiResult.Success && generation==detailGeneration) loadComponentNames(result.value,generation)
      }
    }
  }
  fun newProduct() {
    if (!canWrite) return
    mutableMutation.value = ProductMutation.Idle
    clearComposition()
    detailController.close(); detailId = null
    mutableDraft.value = ProductDraft()
  }
  fun edit(product: Product) {
    if (!canWrite || !product.canEdit(accountId)) return
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
    if (input.original != null && !input.original.canEdit(accountId)) return
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
          mutableDraft.value = null; clearComposition(); detailController.close(); detailId = null
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
    val recipe = composition.value
    val id = recipe?.original?.id ?: input?.original?.id ?: detailId
    mutableMutation.value = ProductMutation.Rechecking
    viewModelScope.launch {
      if (id != null) {
        when (val result = repository.get(id)) {
          is ApiResult.Success -> {
            mutableDraft.value = null
            clearComposition()
            detailId = id
            detailController.close(); detailController.load { result }
            listController.reload()
            mutableMutation.value = ProductMutation.Notice("Данные перечитаны с сервера.")
          }
          is ApiResult.Failure -> mutableMutation.value = ProductMutation.Failure(result.error,true)
        }
      } else {
        listController.search((recipe?.name ?: input?.name)?.trim().orEmpty())?.join()
        if (catalog.value.phase == CatalogPhase.DATA || catalog.value.phase == CatalogPhase.EMPTY) {
          mutableDraft.value = null; clearComposition(); detailController.close(); detailId = null
          mutableMutation.value = ProductMutation.Notice("Проверьте каталог: результат предыдущего сохранения мог уже появиться.")
        } else mutableMutation.value = ProductMutation.Failure(catalog.value.error ?: ApiError(ApiError.Kind.NETWORK),true)
      }
    }
  }
  fun back() {
    if (!canWrite && (mutation.value as? ProductMutation.Failure)?.uncertain != true) return
    if ((mutation.value as? ProductMutation.Failure)?.uncertain == true) { checkAfterFailure(); return }
    mutableDraft.value = null
    clearComposition()
    detailId = null; detailController.close()
    mutableMutation.value = ProductMutation.Idle
    listController.reload()
  }
  fun newComposition() {
    if (!canWrite) return
    clearComposition()
    mutableDraft.value = null
    detailId = null; detailController.close()
    mutableMutation.value = ProductMutation.Idle
    mutableComposition.value = CompositionDraft()
  }
  fun editComposition(product: Product) {
    if (!canWrite || !product.canEdit(accountId)) return
    clearComposition()
    mutableDraft.value = null
    mutableMutation.value = ProductMutation.Idle
    mutableComposition.value = CompositionDraft.from(product)
    val generation = compositionGeneration
    // Names are presentation only; a failed name read never removes the saved link or its precision.
    viewModelScope.launch {
      for (item in product.components) {
        if (generation != compositionGeneration) break
        val cached=componentNames.value[item.productId]
        val result=if(cached==null) repository.get(item.productId) else null
        val name=cached ?: (result as? ApiResult.Success)?.value?.name
        if (generation == compositionGeneration && name != null) {
          mutableComposition.value = composition.value?.let { form -> form.copy(ingredients = form.ingredients.map {
            if (it.productId == item.productId) it.copy(name=name) else it
          }) }
        }
      }
    }
  }
  fun updateComposition(change: (CompositionDraft) -> CompositionDraft) {
    if (canWrite) mutableComposition.value = composition.value?.let { change(it).copy(errors=emptyMap()) }
  }
  fun changePercent(id: String, value: String) = updateComposition { form ->
    form.copy(ingredients=form.ingredients.map { if (it.productId == id) it.copy(percent=value) else it })
  }
  fun removeComponent(id: String) = updateComposition { form -> form.copy(ingredients=form.ingredients.filterNot { it.productId == id }) }
  fun chooseComponent(): ProductSelectionRequest? {
    if (!canWrite || composition.value == null || composition.value!!.ingredients.size >= 100) return null
    return ProductSelectionRequest.create(ProductSelectionPurpose.COMPOSITION).also { componentRequest=it }
  }
  fun acceptComponent(result: ProductSelectionResult) {
    if (result.request != componentRequest || result.request.purpose != ProductSelectionPurpose.COMPOSITION || !canWrite) return
    componentRequest = null
    val form = composition.value ?: return
    if (form.ingredients.any { it.productId == result.selection.productId }) {
      mutableComposition.value = form.copy(errors=mapOf("components" to "Ингредиент уже добавлен.")); return
    }
    val generation = compositionGeneration
    mutableResolvingComponent.value = true
    viewModelScope.launch {
      val loaded = repository.get(result.selection.productId)
      if (generation != compositionGeneration) return@launch
      mutableResolvingComponent.value = false
      when (loaded) {
        is ApiResult.Success -> {
          val product = loaded.value
          if (product.isDeleted || (product.ownerId != null && product.ownerId != accountId)) {
            mutableComposition.value = composition.value?.copy(errors=mapOf("components" to "Этот продукт недоступен для нового состава."))
          } else mutableComposition.value = composition.value?.let { current ->
            current.copy(ingredients=current.ingredients + CompositionIngredient(product.id,product.name,""), errors=emptyMap())
          }
        }
        is ApiResult.Failure -> mutableMutation.value = ProductMutation.Failure(loaded.error,false)
      }
    }
  }
  fun saveComposition() {
    if (!canWrite) return
    val input = composition.value ?: return
    if (input.original != null && !input.original.canEdit(accountId)) return
    val errors = input.validationErrors()
    if (errors.isNotEmpty()) { mutableComposition.value=input.copy(errors=errors,yieldExpanded=input.yieldExpanded || errors.containsKey("yield")); return }
    mutableMutation.value = ProductMutation.Busy
    viewModelScope.launch {
      val request = input.request()
      val result = if (input.original == null) repository.create(ProductCreate.Composite(input.name.trim(),request))
          else repository.writeComposition(input.original.id,request)
      when (result) {
        is ApiResult.Success -> {
          mutableMutation.value=ProductMutation.Idle
          open(result.value.id)
          listController.reload()
          mutableMutation.value=ProductMutation.Notice("Состав сохранён.")
        }
        is ApiResult.Failure -> failed(result.error)
      }
    }
  }
  private fun clearComposition() {
    compositionGeneration++
    mutableComposition.value=null
    mutableResolvingComponent.value=false
    componentRequest=null
  }
  private fun loadComponentNames(product: Product, generation: Int) {
    viewModelScope.launch {
      for (item in product.components) {
        if (generation != detailGeneration) break
        val result=repository.get(item.productId)
        if (generation==detailGeneration && result is ApiResult.Success) {
          mutableComponentNames.value=componentNames.value + (item.productId to result.value.name)
        }
      }
    }
  }
  fun retryDetail() { detailId?.let(::open) }
  fun leave() { detailGeneration++; listController.close(); detailController.close() }
  class Factory(private val repository: ProductRepository, private val accountId: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
      require(modelClass == ProductsViewModel::class)
      @Suppress("UNCHECKED_CAST") return ProductsViewModel(repository,accountId) as T
    }
  }
}
