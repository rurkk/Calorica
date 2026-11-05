package com.rurkk.calorica

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.ProductRepository
import com.rurkk.calorica.core.navigation.*
import com.rurkk.calorica.core.ui.ReadState
import com.rurkk.calorica.features.products.*
import java.math.BigDecimal
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductsFeatureTest {
  private val fixtures = ApiJson.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures.json")).readText()).jsonArray
  private val base = ApiJson.decodeFromJsonElement<ProductPage>(fixtures.first { it.jsonObject["name"]?.jsonPrimitive?.content=="search" }.jsonObject.getValue("response")).items.first()
  private val owner = "00000000-0000-4000-8000-000000000004"
  private val personal get() = base.copy(ownerId=owner,name="Мой продукт")
  private open class Repository : ProductRepository {
    val searches = mutableListOf<Pair<String?,String?>>()
    val gets = mutableListOf<String>()
    val creates = mutableListOf<ProductCreate>()
    val patches = mutableListOf<ProductPatch>()
    val deletes = mutableListOf<Pair<String,String>>()
    var searchReply: suspend (String?,String?) -> ApiResult<ProductPage> = { _,_ -> ApiResult.Success(ProductPage(emptyList(),null)) }
    var getReply: suspend (String) -> ApiResult<Product> = { error("unexpected get") }
    var createReply: suspend (ProductCreate) -> ApiResult<Product> = { error("unexpected create") }
    var patchReply: suspend (String,ProductPatch) -> ApiResult<Product> = { _,_ -> error("unexpected patch") }
    var deleteResult: suspend () -> ApiResult<Unit> = { ApiResult.Success(Unit) }
    override suspend fun search(q:String?,limit:Int,cursor:String?):ApiResult<ProductPage> { searches+=q to cursor; return searchReply(q,cursor) }
    override suspend fun get(id:String):ApiResult<Product> { gets+=id; return getReply(id) }
    override suspend fun create(request:ProductCreate):ApiResult<Product> { creates+=request; return createReply(request) }
    override suspend fun patch(id:String,request:ProductPatch):ApiResult<Product> { patches+=request; return patchReply(id,request) }
    override suspend fun writeComposition(id:String,request:CompositionWrite):ApiResult<Product> = error("outside T06")
    override suspend fun delete(id:String,mode:String):ApiResult<Unit> { deletes+=id to mode; return deleteResult() }
  }
  @Test fun searchCancelsAndRejectsLateNonCooperativeResponses() = runTest {
    val repo=Repository().apply { searchReply={ q,_ ->
      if(q=="old") withContext(NonCancellable) { delay(1000) }
      ApiResult.Success(ProductPage(listOf(base.copy(name=q ?: "all")),null))
    } }
    val list=ProductListController(backgroundScope,repo)
    list.search("old"); runCurrent()
    list.search("fresh"); runCurrent()
    assertEquals("fresh",list.state.value.items.single().name)
    advanceTimeBy(1001); runCurrent()
    assertEquals("fresh",list.state.value.items.single().name)
    list.close(); assertEquals(CatalogPhase.IDLE,list.state.value.phase)
  }
  @Test fun typingDebouncesAndOversizedQueryNeverHitsApi() = runTest {
    val repo=Repository()
    val list=ProductListController(backgroundScope,repo)
    list.search("c",true); advanceTimeBy(100)
    list.search("ca",true); advanceTimeBy(100)
    list.search("cat",true); advanceTimeBy(301); runCurrent()
    assertEquals(listOf("cat" to null),repo.searches)
    assertEquals(CatalogPhase.EMPTY,list.state.value.phase)
    list.search("x".repeat(201)); runCurrent()
    assertEquals(CatalogPhase.ERROR,list.state.value.phase)
    assertEquals(1,repo.searches.size)
  }
  @Test fun searchLengthCountsUnicodeCodePointsLikeServer() = runTest {
    val repo=Repository()
    val list=ProductListController(backgroundScope,repo)
    list.search("😀".repeat(200)); runCurrent()
    assertEquals(1,repo.searches.size)
    assertEquals(CatalogPhase.EMPTY,list.state.value.phase)
    list.search("😀".repeat(201)); runCurrent()
    assertEquals(1,repo.searches.size)
    assertEquals(CatalogPhase.ERROR,list.state.value.phase)
  }
  @Test fun paginationSuppressesDoubleTapAndKeepsReadErrorDistinctFromEmpty() = runTest {
    val gate=CompletableDeferred<Unit>()
    val repo=Repository().apply { searchReply={ _,cursor ->
      if(cursor==null) ApiResult.Success(ProductPage(listOf(base),"next"))
      else { gate.await(); ApiResult.Failure(ApiError(ApiError.Kind.NETWORK)) }
    } }
    val list=ProductListController(backgroundScope,repo)
    list.search(""); runCurrent()
    list.nextPage(); list.nextPage(); runCurrent()
    assertEquals(2,repo.searches.size)
    gate.complete(Unit); runCurrent()
    assertEquals(CatalogPhase.DATA,list.state.value.phase)
    assertEquals(listOf(base),list.state.value.items)
    assertNotNull(list.state.value.error)
    assertEquals("next",list.state.value.nextCursor)
  }
  @Test fun changingSearchInvalidatesPendingPageAndCannotAppendToNewQuery() = runTest {
    val repo=Repository().apply { searchReply={ q,cursor ->
      if(cursor!=null) withContext(NonCancellable) { delay(1000) }
      ApiResult.Success(ProductPage(listOf(base.copy(name=q ?: "all")),if(q=="old" && cursor==null) "next" else null))
    } }
    val list=ProductListController(backgroundScope,repo)
    list.search("old"); runCurrent(); list.nextPage(); runCurrent()
    list.search("fresh"); runCurrent(); advanceTimeBy(1001); runCurrent()
    assertEquals(listOf("fresh"),list.state.value.items.map { it.name })
  }
  @Test fun leafDraftUsesExactDecimalAndOmitsAllUneditedOrHiddenValues() {
    val product=personal.copy(productLabelIds=listOf("00000000-0000-4000-8000-000000000006"))
    val draft=ProductDraft.from(product).copy(name="Новое имя")
    val json=draft.patch().json()
    assertEquals(setOf("name"),json.keys)
    assertFalse(json.values.any { it==JsonNull })
    val comma=draft.copy(calories="12,34",protein="0",fat="0",carbs="0")
    assertEquals(BigDecimal("12.34"),comma.nutrition().calories)
    assertTrue(comma.copy(calories="0.001").validationErrors().containsKey("calories"))
    assertTrue(comma.copy(calories="100000").validationErrors().containsKey("calories"))
    assertTrue(comma.copy(calories="1e2").validationErrors().containsKey("calories"))
    assertTrue(comma.copy(calories="-1").validationErrors().containsKey("calories"))
  }
  @Test fun personalActionsNeverApplyToBaseForeignDeletedOrCompositeNutrition() {
    assertFalse(base.canEditLeaf(owner)); assertFalse(base.canDelete(owner))
    assertFalse(personal.canEditLeaf("other")); assertFalse(personal.canDelete("other"))
    assertFalse(personal.copy(isDeleted=true).canEditLeaf(owner))
    assertFalse(personal.copy(kind="COMPOSITE").canEditLeaf(owner))
    assertTrue(personal.canEditLeaf(owner)); assertTrue(personal.canDelete(owner))
  }
  @Test fun saveIsSingleFlightAndRereadsBothDetailAndCatalog() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val gate=CompletableDeferred<Unit>()
      val repo=Repository().apply { createReply={ gate.await(); ApiResult.Success(personal) }; getReply={ ApiResult.Success(personal) } }
      val vm=ProductsViewModel(repo,owner)
      vm.newProduct(); vm.change("name","Продукт"); vm.change("calories","10"); vm.change("protein","1"); vm.change("fat","2"); vm.change("carbs","3")
      vm.save(); vm.save(); runCurrent()
      assertEquals(1,repo.creates.size); assertEquals(ProductMutation.Busy,vm.mutation.value)
      gate.complete(Unit); runCurrent()
      assertNull(vm.draft.value)
      assertEquals(listOf(personal.id),repo.gets)
      assertEquals(1,repo.searches.size)
      assertEquals(personal,(vm.detail.value as ReadState.Data<Product>).value)
    } finally { Dispatchers.resetMain() }
  }
  @Test fun ambiguousCreateCannotBeResubmittedBeforeSuccessfulRead() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val gate=CompletableDeferred<Unit>()
      val repo=Repository().apply {
        createReply={ ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT)) }
        searchReply={ _,_ -> gate.await(); ApiResult.Success(ProductPage(listOf(personal),null)) }
      }
      val vm=ProductsViewModel(repo,owner)
      vm.newProduct(); vm.change("name","Продукт"); for(field in listOf("calories","protein","fat","carbs")) vm.change(field,"1")
      vm.save(); runCurrent(); vm.save(); runCurrent()
      assertEquals(1,repo.creates.size); assertFalse(vm.canWrite)
      vm.checkAfterFailure(); runCurrent(); vm.save(); vm.newProduct()
      assertEquals(ProductMutation.Rechecking,vm.mutation.value)
      assertEquals(1,repo.creates.size); assertFalse(vm.canWrite)
      gate.complete(Unit); runCurrent()
      assertTrue(vm.canWrite); assertNull(vm.draft.value)
      assertEquals("Продукт",repo.searches.single().first)
    } finally { Dispatchers.resetMain() }
  }
  @Test fun failedReconciliationKeepsUncertainWriteGateClosed() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val repo=Repository().apply { createReply={ ApiResult.Failure(ApiError(ApiError.Kind.NETWORK)) }; searchReply={ _,_ -> ApiResult.Failure(ApiError(ApiError.Kind.NETWORK)) } }
      val vm=ProductsViewModel(repo,owner)
      vm.newProduct(); vm.change("name","Продукт"); for(field in listOf("calories","protein","fat","carbs")) vm.change(field,"1")
      vm.save(); runCurrent(); vm.checkAfterFailure(); runCurrent(); vm.save()
      assertFalse(vm.canWrite); assertEquals(1,repo.creates.size)
      assertTrue((vm.mutation.value as ProductMutation.Failure).uncertain)
    } finally { Dispatchers.resetMain() }
  }
  @Test fun deleteUsesKeepReferencesAndRereadsListWithoutDeletingBase() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val repo=Repository()
      val vm=ProductsViewModel(repo,owner)
      vm.delete(base); runCurrent(); assertTrue(repo.deletes.isEmpty())
      vm.delete(personal); vm.delete(personal); runCurrent()
      assertEquals(listOf(personal.id to "KEEP_REFERENCES"),repo.deletes)
      assertEquals(1,repo.searches.size)
      assertEquals(ReadState.Idle,vm.detail.value)
    } finally { Dispatchers.resetMain() }
  }
  @Test fun sharedPickerDeliversOnlyUuidOnceForTheOriginalRequestAndRejectsDeleted() {
    val request=ProductSelectionRequest.create(ProductSelectionPurpose.DIARY)
    val delivery=ProductSelectionDelivery(request)
    assertNull(delivery.select(base.copy(isDeleted=true)))
    val selection=requireNotNull(delivery.select(base))
    val result=ProductSelectionResult(request,selection)
    assertEquals(base.id,result.selection.productId)
    assertEquals(request,ProductSelectionResult.decode(result.encode()).request)
    assertNull(delivery.select(base)); assertFalse(delivery.cancel())
  }
}
