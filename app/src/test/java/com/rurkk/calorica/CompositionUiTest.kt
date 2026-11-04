package com.rurkk.calorica

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.*
import com.rurkk.calorica.core.navigation.CaloricaNavHost
import com.rurkk.calorica.core.session.*
import com.rurkk.calorica.core.ui.ConnectionViewModel
import com.rurkk.calorica.features.auth.AuthViewModel
import com.rurkk.calorica.features.products.ProductsFeatureScreens
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Runs the actual root navigation, shared picker and product editor, including Activity recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CompositionUiTest {
  @get:Rule val compose=createEmptyComposeRule()
  private lateinit var activity: ActivityController<CompositionTestActivity>

  @Before fun start() {
    CompositionTestActivity.products=object:CompositionRepository() {
      override suspend fun create(request:ProductCreate):ApiResult<Product> {
        creates+=request
        val input=request as ProductCreate.Composite
        val saved=compositionProduct(10+creates.size,true).copy(name=input.name,
            components=listOf(Component((input.composition as CompositionWrite.Percent).components.single().productId,BigDecimal.ONE,null)))
        products[saved.id]=saved
        return ApiResult.Success(saved)
      }
    }.apply { products[compositionId(2)]=compositionProduct(2).copy(name="Ингредиент") }
    CompositionTestActivity.session=testSession()
    activity=Robolectric.buildActivity(CompositionTestActivity::class.java).setup().visible()
    compose.waitForIdle()
  }
  @After fun finish() { activity.pause().stop().destroy() }

  @Test fun nestedPickerReturnsOnlyToItsCallerAndCancellationAndRotationKeepBothForms() {
    click("Продукты")
    click("Создать состав")
    compose.onNodeWithText("Название состава").performTextInput("Наружный")
    click("Добавить ингредиент")
    click("Создать состав")
    compose.onNodeWithText("Название состава").performTextInput("Вложенный")
    click("Добавить ингредиент")
    compose.runOnIdle { activity.recreate() }
    compose.waitForIdle()
    click("Выбрать")
    compose.onNodeWithText("Вложенный").assertExists()
    compose.onNodeWithText("Наружный").assertDoesNotExist()
    compose.onNodeWithText("Доля, %").performScrollTo().performTextInput("100")
    click("Настроить общий выход (необязательно)")
    compose.onNodeWithText("Общий коэффициент выхода").performScrollTo().performTextInput("0.800000000001")
    compose.runOnIdle { activity.recreate() }
    compose.waitForIdle()
    compose.onNodeWithText("0.800000000001").assertExists()
    click("Сохранить состав")
    click("Выбрать продукт")
    compose.onNodeWithText("Наружный").assertExists()
    compose.onNodeWithText("Вложенный").assertExists()
    compose.onNodeWithText("Доля, %").performScrollTo().performTextInput("100")
    click("Добавить ингредиент")
    click("Отмена выбора")
    compose.onNodeWithText("Наружный").assertExists()
    compose.onAllNodesWithText("Доля, %").assertCountEquals(1)
    click("Сохранить состав")
    val writes=CompositionTestActivity.products.creates.map { it as ProductCreate.Composite }
    assertEquals(listOf("Вложенный","Наружный"),writes.map { it.name })
    assertEquals(compositionId(2),(writes[0].composition as CompositionWrite.Percent).components.single().productId)
    assertEquals(compositionId(11),(writes[1].composition as CompositionWrite.Percent).components.single().productId)
    assertEquals(PatchField.Set(BigDecimal("0.800000000001")),(writes[0].composition as CompositionWrite.Percent).manualYieldFactor)
    compose.onNodeWithText("Калории: 987.65 ккал").assertExists()
  }

  @Test fun ingredientYieldIsReadOnlyOnActualFormAndSavingOmitsHiddenCoefficients() {
    val recipe=compositionProduct(1,true).copy(components=listOf(Component(compositionId(2),BigDecimal.ONE,BigDecimal("0.75"))),
        yieldSource="INGREDIENTS",effectiveYieldFactor=BigDecimal("0.75"))
    compose.runOnIdle { CompositionTestActivity.products.products[recipe.id]=recipe }
    click("Продукты")
    click(recipe.name)
    click("Состав и выход")
    click("Настроить общий выход (необязательно)")
    compose.onNodeWithText("Выход: 0.75 · по ингредиентам").assertExists()
    compose.onNodeWithText("Общий коэффициент выхода").assertDoesNotExist()
    compose.onNodeWithText("Убрать настройку выхода").assertDoesNotExist()
    click("Сохранить состав")
    val request=CompositionTestActivity.products.writes.single() as CompositionWrite.Percent
    assertEquals(PatchField.Unchanged,request.manualYieldFactor)
    assertEquals(PatchField.Unchanged,request.components.single().yieldFactor)
  }

  private fun click(text:String) {
    val node=compose.onNode(hasText(text) and hasClickAction())
    runCatching { node.performScrollTo() }
    node.performClick()
    compose.waitForIdle()
  }
  private fun testSession():SessionCoordinator {
    val account=Account(COMPOSITION_OWNER,"test@example.test","USER",Profile(null,null,null,null))
    val tokens=Tokens("test-access","test-refresh","Bearer","2099-01-01T00:00:00Z","2099-01-01T00:00:00Z",account)
    val auth=object:AuthRepository {
      override suspend fun login(request:Credentials)=ApiResult.Success(tokens)
      override suspend fun register(request:Credentials)=ApiResult.Success(tokens)
      override suspend fun refresh(request:Refresh)=ApiResult.Success(tokens)
      override suspend fun logout()=ApiResult.Success(Unit)
      override suspend fun me()=ApiResult.Success(account)
    }
    val storage=object:RefreshTokenStore {
      override suspend fun read():String?=null
      override suspend fun write(token:String) { }
      override suspend fun clear() { }
    }
    return SessionCoordinator(auth,storage,InMemorySession()).also { runBlocking { it.authenticate(Credentials("test@example.test","password"),false) } }
  }
}

class CompositionTestActivity:ComponentActivity() {
  override fun onCreate(savedInstanceState:Bundle?) {
    super.onCreate(savedInstanceState)
    val factory=object:ViewModelProvider.Factory {
      override fun <T:ViewModel> create(modelClass:Class<T>):T {
        @Suppress("UNCHECKED_CAST")
        return (if(modelClass==AuthViewModel::class.java) AuthViewModel(session)
          else ConnectionViewModel(object:ConnectionRepository { override suspend fun check()=ApiResult.Success(Readiness("UP")) })) as T
      }
    }
    val diary=object:DiaryRepository {
      override suspend fun mealLabels()=ApiResult.Success(emptyList<MealLabel>())
      override suspend fun day(date:String,zone:String)=ApiResult.Success(DiaryDay(date,zone,emptyList(),Nutrition(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)))
      override suspend fun create(request:DiaryWrite):ApiResult<DiaryEntry> = error("Not used")
      override suspend fun patch(id:String,request:DiaryPatch):ApiResult<DiaryEntry> = error("Not used")
      override suspend fun delete(id:String):ApiResult<Unit> = error("Not used")
    }
    setContent { MaterialTheme {
      CaloricaNavHost(viewModel(factory=factory),viewModel(factory=factory),ProductsFeatureScreens(products,diary,session))
    } }
  }
  companion object {
    internal lateinit var products:CompositionRepository
    internal lateinit var session:SessionCoordinator
  }
}
