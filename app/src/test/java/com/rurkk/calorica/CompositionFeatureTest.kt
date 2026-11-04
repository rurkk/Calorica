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

internal const val COMPOSITION_OWNER = "00000000-0000-4000-8000-000000000001"
internal fun compositionProduct(index: Int, composite: Boolean = false): Product {
  val nutrition=NutritionInputSet(BigDecimal("123.45"),BigDecimal("1.2"),BigDecimal("2.3"),BigDecimal("3.4"))
  return Product("00000000-0000-4000-8000-${index.toString().padStart(12,'0')}",COMPOSITION_OWNER,"Продукт $index",false,
      if(composite) "COMPOSITE" else "LEAF",if(composite) null else nutrition,
      Nutrition(BigDecimal("987.654321"),BigDecimal("7.12345"),BigDecimal("8"),BigDecimal("9")),
      if(composite) listOf(Component(compositionId(2),BigDecimal.ONE,null)) else emptyList(),
      null,BigDecimal.ONE,"DEFAULT",listOf("label"))
}
internal fun compositionId(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12,'0')}"
internal open class CompositionRepository : ProductRepository {
  val products=mutableMapOf<String,Product>()
  val writes=mutableListOf<CompositionWrite>()
  val creates=mutableListOf<ProductCreate>()
  val patches=mutableListOf<ProductPatch>()
  val gets=mutableListOf<String>()
  var writeReply: suspend () -> ApiResult<Product> = { ApiResult.Success(products.values.first()) }
  override suspend fun search(q:String?,limit:Int,cursor:String?) = ApiResult.Success(ProductPage(products.values.filter { q==null || it.name.contains(q) },null))
  override suspend fun get(id:String):ApiResult<Product> { gets+=id; return products[id]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError(ApiError.Kind.HTTP,404)) }
  override suspend fun create(request:ProductCreate):ApiResult<Product> { creates+=request; return writeReply() }
  override suspend fun patch(id:String,request:ProductPatch):ApiResult<Product> { patches+=request; return writeReply() }
  override suspend fun writeComposition(id:String,request:CompositionWrite):ApiResult<Product> { writes+=request; return writeReply() }
  override suspend fun delete(id:String,mode:String) = ApiResult.Success(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CompositionFeatureTest {
  private val recipe=compositionProduct(1,true)
  private fun ingredient(index:Int,percent:String) = CompositionIngredient(compositionId(index),"Ингредиент $index",percent)

  @Test fun equalThirdsReopenAndResaveExactlyOneHundredWithoutRounding() {
    val product=recipe.copy(components=listOf("0.333333333334","0.333333333333","0.333333333333").mapIndexed { i,value -> Component(compositionId(i+2),BigDecimal(value),null) })
    val draft=CompositionDraft.from(product)
    assertEquals(listOf("33.3333333334","33.3333333333","33.3333333333"),draft.ingredients.map { it.percent })
    assertEquals(0,draft.totalPercent!!.compareTo(BigDecimal("100")))
    assertTrue(draft.validationErrors().isEmpty())
    val json=draft.request().json()
    assertEquals(setOf("inputMode","components"),json.keys)
    assertEquals(draft.ingredients.map { it.percent },json.getValue("components").jsonArray.map { it.jsonObject.getValue("percent").jsonPrimitive.content })
    assertTrue(json.getValue("components").jsonArray.all { it.jsonObject.keys==setOf("productId","percent") })
  }
  @Test fun percentagesRequireExactHundredAndStrictPositiveContractPrecision() {
    for(total in listOf("90","110","99.9999999999","100.0000000001")) {
      assertTrue(CompositionDraft(name="Блюдо",ingredients=listOf(ingredient(2,total))).validationErrors().isNotEmpty())
    }
    val valid=CompositionDraft(name="Блюдо",ingredients=listOf(ingredient(2,"0,0000000001"),ingredient(3,"99,9999999999")))
    assertTrue(valid.validationErrors().isEmpty())
    assertEquals("0.0000000001",valid.request().components.first().percent.toPlainString())
    for(bad in listOf("0","-1","NaN","1e2","100.00000000001","01","", "9".repeat(1000))) {
      assertTrue(CompositionDraft(name="Блюдо",ingredients=listOf(ingredient(2,bad))).validationErrors().containsKey(compositionId(2)))
    }
  }
  @Test fun unchangedGeneralYieldAbsentExplicitClearNullAndExplicitOneNotDefault() {
    val draft=CompositionDraft.from(recipe.copy(manualYieldFactor=BigDecimal("0.800000000001"),yieldSource="MANUAL"))
    assertFalse(draft.request().json().containsKey("manualYieldFactor"))
    assertEquals(JsonNull,draft.copy(manualYield="",yieldEdited=true).request().json()["manualYieldFactor"])
    assertEquals(JsonPrimitive("1"),draft.copy(manualYield="1",yieldEdited=true).request().json()["manualYieldFactor"])
    assertEquals(JsonPrimitive("0.800000000001"),draft.copy(manualYield="0,800000000001",yieldEdited=true).request().json()["manualYieldFactor"])
  }
  @Test fun factorRangeAndPrecisionAreCheckedWithoutRounding() {
    val draft=CompositionDraft.from(recipe).copy(yieldEdited=true)
    for(value in listOf("0.000000000001","9999.999999999999","1","0,8")) assertTrue(draft.copy(manualYield=value).validationErrors().isEmpty())
    for(value in listOf("0","10000","1.0000000000001","-1","NaN","1e1")) assertTrue(draft.copy(manualYield=value).validationErrors().containsKey("yield"))
  }
  @Test fun ingredientCoefficientsIncludingExplicitOneAreReadOnlyAndOmitted() {
    val product=recipe.copy(components=listOf(Component(compositionId(2),BigDecimal("0.5"),BigDecimal.ONE),Component(compositionId(3),BigDecimal("0.5"),null)),yieldSource="INGREDIENTS")
    val draft=CompositionDraft.from(product).copy(manualYield="0.8",yieldEdited=true)
    assertTrue(draft.ingredientYield)
    val changed=draft.copy(ingredients=listOf(ingredient(2,"60"),ingredient(3,"40")))
    val json=changed.request().json()
    assertFalse(json.containsKey("manualYieldFactor"))
    assertTrue(json.getValue("components").jsonArray.all { !it.jsonObject.containsKey("yieldFactor") })
    assertFalse(json.containsKey("productLabelIds"))
    assertFalse(json.containsKey("manualNutrition"))
  }
  @Test fun removingAndReaddingSameChildPreservesItsHiddenModeById() {
    val product=recipe.copy(components=listOf(Component(compositionId(2),BigDecimal.ONE,BigDecimal("0.5"))))
    val draft=CompositionDraft.from(product)
    assertFalse(draft.copy(ingredients=listOf(ingredient(3,"100"))).ingredientYield)
    val readded=draft.copy(ingredients=listOf(ingredient(2,"100")))
    assertTrue(readded.ingredientYield)
    assertEquals(PatchField.Unchanged,readded.request().components.single().yieldFactor)
  }
  @Test fun returningToLeafRequiresMissingManualNutritionAndSendsItAtomically() {
    val draft=CompositionDraft.from(recipe).copy(ingredients=emptyList())
    assertEquals(setOf("calories","protein","fat","carbs"),draft.validationErrors().keys)
    val completed=draft.copy(leafNutrition=ProductDraft(calories="12,34",protein="0",fat="1",carbs="2"))
    val json=completed.request().json()
    assertEquals(JsonArray(emptyList()),json["components"])
    assertEquals("12.34",json.getValue("manualNutrition").jsonObject.getValue("calories").jsonPrimitive.content)
    assertFalse(json.containsKey("manualYieldFactor"))
  }
  @Test fun leafCompositeLeafKeepsExistingManualNutritionAndLabelsUntouched() {
    val leaf=compositionProduct(1)
    val toComposite=CompositionDraft.from(leaf).copy(ingredients=listOf(ingredient(2,"100")))
    assertEquals(PatchField.Unchanged,toComposite.request().manualNutrition)
    val back=CompositionDraft.from(recipe.copy(manualNutrition=leaf.manualNutrition)).copy(ingredients=emptyList())
    assertTrue(back.validationErrors().isEmpty())
    assertEquals(PatchField.Unchanged,back.request().manualNutrition)
  }
  @Test fun compositeNameEditDoesNotSendCalculatedNutritionOrHiddenSettings() {
    val draft=ProductDraft.from(recipe).copy(name="Новое имя")
    assertFalse(draft.editsNutrition)
    assertTrue(draft.validationErrors().isEmpty())
    assertEquals(buildJsonObject { put("name","Новое имя") },draft.patch().json())
  }
  @Test fun compositionSaveIsSingleFlightAndShowsOnlyServerNutrition() = withMain {
    val gate=CompletableDeferred<Unit>()
    val repo=CompositionRepository().apply { products[recipe.id]=recipe; writeReply={ gate.await(); ApiResult.Success(recipe) } }
    val vm=ProductsViewModel(repo,COMPOSITION_OWNER)
    vm.editComposition(recipe); runCurrent()
    vm.saveComposition(); vm.saveComposition(); runCurrent()
    assertEquals(1,repo.writes.size)
    assertEquals(ProductMutation.Busy,vm.mutation.value)
    gate.complete(Unit); runCurrent()
    assertNull(vm.composition.value)
    assertEquals(recipe.nutritionPer100g,(vm.detail.value as ReadState.Data).value.nutritionPer100g)
  }
  @Test fun invalidNinetyPercentNeverStartsRequest() = withMain {
    val repo=CompositionRepository()
    val vm=ProductsViewModel(repo,COMPOSITION_OWNER)
    vm.editComposition(recipe); runCurrent(); vm.changePercent(compositionId(2),"90"); vm.saveComposition(); runCurrent()
    assertTrue(repo.writes.isEmpty()); assertTrue(vm.composition.value!!.errors.containsKey("total"))
  }
  @Test fun cycleAndPermissionErrorsKeepDraftAndAreNeverLocallyRecalculated() = withMain {
    for(code in listOf("CYCLE_DETECTED","FORBIDDEN")) {
      val error=ApiError(ApiError.Kind.HTTP,422,code,"trace")
      val repo=CompositionRepository().apply { writeReply={ ApiResult.Failure(error) } }
      val vm=ProductsViewModel(repo,COMPOSITION_OWNER)
      vm.editComposition(recipe); runCurrent(); vm.saveComposition(); runCurrent()
      assertNotNull(vm.composition.value)
      assertEquals(error,(vm.mutation.value as ProductMutation.Failure).error)
      assertTrue(vm.canWrite)
      assertFalse(vm.detail.value is ReadState.Data)
    }
  }
  @Test fun ambiguousCompositionCannotBeRetriedUntilSuccessfulServerRead() = withMain {
    val repo=CompositionRepository().apply { products[recipe.id]=recipe; writeReply={ ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT)) } }
    val vm=ProductsViewModel(repo,COMPOSITION_OWNER)
    vm.editComposition(recipe); runCurrent(); vm.saveComposition(); runCurrent(); vm.saveComposition(); runCurrent()
    assertEquals(1,repo.writes.size); assertFalse(vm.canWrite)
    vm.checkAfterFailure(); runCurrent()
    assertNull(vm.composition.value); assertTrue(vm.canWrite)
  }
  @Test fun pickerAcceptsNestedProductOnceForMatchingRequestAndRetainsExactDraftAcrossLeave() = withMain {
    val repo=CompositionRepository().apply { products[recipe.id]=recipe }
    val vm=ProductsViewModel(repo,COMPOSITION_OWNER)
    vm.newComposition(); vm.updateComposition { it.copy(name="Черновик",manualYield="0.800000000001",yieldEdited=true,yieldExpanded=true) }
    val request=vm.chooseComponent()!!
    vm.leave(); vm.enter()
    vm.acceptComponent(ProductSelectionResult(ProductSelectionRequest.create(ProductSelectionPurpose.COMPOSITION),ProductSelection(recipe.id)))
    runCurrent(); assertTrue(repo.gets.isEmpty())
    val result=ProductSelectionResult(request,ProductSelection(recipe.id))
    vm.acceptComponent(result); vm.acceptComponent(result); runCurrent()
    assertEquals(listOf(recipe.id),repo.gets)
    assertEquals(recipe.id,vm.composition.value!!.ingredients.single().productId)
    assertEquals("Черновик",vm.composition.value!!.name)
    assertEquals("0.800000000001",vm.composition.value!!.manualYield)
    vm.changePercent(recipe.id,"100"); vm.saveComposition(); runCurrent()
    assertEquals(1,repo.creates.size)
    assertTrue(repo.creates.single() is ProductCreate.Composite)
  }
  @Test fun pickerRejectsDeletedForeignAndDuplicateComponents() = withMain {
    for(product in listOf(recipe.copy(isDeleted=true),recipe.copy(ownerId="other"))) {
      val repo=CompositionRepository().apply { products[product.id]=product }
      val vm=ProductsViewModel(repo,COMPOSITION_OWNER); vm.newComposition()
      vm.acceptComponent(ProductSelectionResult(vm.chooseComponent()!!,ProductSelection(product.id))); runCurrent()
      assertTrue(vm.composition.value!!.ingredients.isEmpty()); assertNotNull(vm.composition.value!!.errors["components"])
    }
    val repo=CompositionRepository().apply { products[recipe.id]=recipe }
    val vm=ProductsViewModel(repo,COMPOSITION_OWNER); vm.newComposition()
    repeat(2) { vm.acceptComponent(ProductSelectionResult(vm.chooseComponent()!!,ProductSelection(recipe.id))); runCurrent() }
    assertEquals(1,vm.composition.value!!.ingredients.size)
    assertEquals(1,repo.gets.size)
  }
  private fun withMain(block:suspend TestScope.()->Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try { block() } finally { Dispatchers.resetMain() }
  }
}
