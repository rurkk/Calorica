package com.rurkk.calorica.features.products

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.ProductRepository
import com.rurkk.calorica.core.data.DiaryRepository
import com.rurkk.calorica.features.diary.DiaryRoute
import com.rurkk.calorica.core.navigation.*
import com.rurkk.calorica.core.session.SessionCoordinator
import com.rurkk.calorica.core.session.SessionState
import com.rurkk.calorica.core.ui.ReadState
import com.rurkk.calorica.core.ui.DisplayNumbers

/** Agent 4 approved this adapter; feature models are scoped to the current nav/session owner. */
class ProductsFeatureScreens(private val repository: ProductRepository, private val diary: DiaryRepository, private val session: SessionCoordinator) : FeatureScreens by FoundationScreens {
  @Composable override fun Diary(picker: ProductPickerHost) { DiaryRoute(diary,repository,picker) }
  @Composable override fun Products(picker: ProductPickerHost) {
    val accountId = (session.state.value as? SessionState.SignedIn)?.account?.id ?: return
    ProductsRoute(repository,accountId)
  }
  @Composable override fun ProductPicker(request: ProductSelectionRequest, onSelected: (ProductSelection) -> Unit, onCancel: () -> Unit) {
    val accountId = (session.state.value as? SessionState.SignedIn)?.account?.id ?: return
    val delivery = remember(request.requestId) { ProductSelectionDelivery(request) }
    ProductsRoute(repository,accountId,
        selectionPurpose=request.purpose,
        onSelect={ product -> delivery.select(product)?.let(onSelected) },
        onCancel={ if (delivery.cancel()) onCancel() })
  }
}

@Composable
fun ProductsRoute(repository: ProductRepository, accountId: String, selectionPurpose: ProductSelectionPurpose? = null,
    onSelect: ((Product) -> Unit)? = null, onCancel: (() -> Unit)? = null) {
  val factory = remember(repository,accountId) { ProductsViewModel.Factory(repository,accountId) }
  val model: ProductsViewModel = viewModel(factory=factory)
  LaunchedEffect(model) { model.enter() }
  DisposableEffect(model) { onDispose { model.leave() } }
  ProductsScreen(model,selectionPurpose,onSelect,onCancel)
}

@Composable
fun ProductsScreen(model: ProductsViewModel, selectionPurpose: ProductSelectionPurpose? = null,
    onSelect: ((Product) -> Unit)? = null, onCancel: (() -> Unit)? = null) {
  val catalog by model.catalog.collectAsStateWithLifecycle()
  val detail by model.detail.collectAsStateWithLifecycle()
  val draft by model.draft.collectAsStateWithLifecycle()
  val mutation by model.mutation.collectAsStateWithLifecycle()
  val busy = mutation == ProductMutation.Busy || mutation == ProductMutation.Rechecking
  val canWrite = model.canWrite
  val selected = (detail as? ReadState.Data<Product>)?.value
  var confirmDelete by remember(selected?.id) { mutableStateOf(false) }
  BackHandler(enabled = draft != null || detail != ReadState.Idle) { model.back() }
  Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
    Text(when(selectionPurpose) {
      ProductSelectionPurpose.DIARY -> "Продукт для дневника"
      ProductSelectionPurpose.COMPOSITION -> "Ингредиент для состава"
      null -> "Продукты"
    },style=MaterialTheme.typography.headlineSmall)
    when (val action=mutation) {
      ProductMutation.Idle -> Unit
      ProductMutation.Busy -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Сохраняем…") }
      ProductMutation.Rechecking -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Перечитываем данные…") }
      is ProductMutation.Notice -> Text(action.message)
      is ProductMutation.Failure -> {
        Text(if(action.uncertain) action.error.writeMessage() else action.error.message(),color=MaterialTheme.colorScheme.error)
        action.error.correlationId?.let { Text("Код обращения: $it",style=MaterialTheme.typography.bodySmall) }
        if(action.uncertain) OutlinedButton(onClick=model::checkAfterFailure) { Text("Перечитать данные") }
      }
    }
    when {
      draft != null -> ProductForm(requireNotNull(draft),canWrite,model::change,model::save,model::back)
      detail != ReadState.Idle -> {
        TextButton(onClick=model::back,enabled=!busy) { Text("К списку") }
        when (val read=detail) {
          ReadState.Loading -> CircularProgressIndicator()
          is ReadState.Error -> {
            ErrorText(read.error)
            OutlinedButton(onClick=model::retryDetail,enabled=!busy) { Text("Повторить загрузку") }
          }
          is ReadState.Data -> ProductCard(read.value,model.accountId,canWrite,
              onEdit={ model.edit(read.value) },onDelete={ confirmDelete=true },onSelect=onSelect?.takeIf { !busy && canWrite })
          else -> Unit
        }
      }
      else -> {
        OutlinedTextField(value=catalog.query,onValueChange=model::search,label={ Text("Поиск продуктов") },
            singleLine=true,isError=catalog.query.codePointCount(0,catalog.query.length)>200,
            supportingText={ if(catalog.query.codePointCount(0,catalog.query.length)>200) Text("Не более 200 символов.") },modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick=model::reload,enabled=catalog.phase!=CatalogPhase.LOADING && !busy) { Text("Обновить") }
          Button(onClick=model::newProduct,enabled=canWrite) { Text("Создать продукт") }
        }
        when(catalog.phase) {
          CatalogPhase.IDLE -> Unit
          CatalogPhase.LOADING -> CircularProgressIndicator()
          CatalogPhase.EMPTY -> Text(if(catalog.query.isBlank()) "Каталог пока пуст." else "По этому запросу ничего не найдено.")
          CatalogPhase.ERROR -> {
            catalog.error?.let { ErrorText(it) }
            OutlinedButton(onClick=model::reload,enabled=catalog.query.codePointCount(0,catalog.query.length)<=200) { Text("Повторить загрузку") }
          }
          CatalogPhase.DATA -> LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(catalog.items,key={ it.id }) { product ->
              OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                  TextButton(onClick={ model.open(product.id) }) { Text(product.name) }
                  Text(if(product.ownerId==null) "Базовый продукт" else "Личный продукт",style=MaterialTheme.typography.bodySmall)
                  Text("${DisplayNumbers.nutrition(product.nutritionPer100g.calories)} ккал / 100 г")
                  if(onSelect!=null && !product.isDeleted) Button(onClick={ onSelect(product) },enabled=!busy) { Text("Выбрать") }
                }
              }
            }
            item {
              catalog.error?.let { ErrorText(it) }
              if(catalog.loadingMore) CircularProgressIndicator()
              else if(catalog.nextCursor!=null) OutlinedButton(onClick=model::nextPage,enabled=!busy) {
                Text(if(catalog.error==null) "Загрузить ещё" else "Повторить загрузку страницы")
              }
            }
          }
        }
      }
    }
    onCancel?.let { TextButton(onClick=it,enabled=!busy) { Text("Отмена выбора") } }
  }
  if(confirmDelete && selected!=null) AlertDialog(
      onDismissRequest={ if(!busy) confirmDelete=false },title={ Text("Удалить продукт?") },
      text={ Text("«${selected.name}» исчезнет из каталога. Записи дневника и существующие составы сохранятся.") },
      confirmButton={ TextButton(onClick={ confirmDelete=false; model.delete(selected) },enabled=canWrite) { Text("Удалить") } },
      dismissButton={ TextButton(onClick={ confirmDelete=false },enabled=!busy) { Text("Отмена") } })
}

@Composable
private fun ColumnScope.ProductForm(draft: ProductDraft,enabled:Boolean,onChange:(String,String)->Unit,onSave:()->Unit,onBack:()->Unit) {
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
    Text(if(draft.original==null) "Новый личный продукт" else "Изменить продукт",style=MaterialTheme.typography.titleLarge)
    Text("КБЖУ на 100 г. Допускается десятичная точка или запятая.")
    for((field,label,value) in listOf(Triple("name","Название",draft.name),Triple("calories","Калории, ккал",draft.calories),
        Triple("protein","Белки, г",draft.protein),Triple("fat","Жиры, г",draft.fat),Triple("carbs","Углеводы, г",draft.carbs))) {
      OutlinedTextField(value=value,onValueChange={ onChange(field,it) },label={ Text(label) },
          singleLine=true,enabled=enabled,isError=draft.errors.containsKey(field),
          supportingText={ draft.errors[field]?.let { Text(it) } },modifier=Modifier.fillMaxWidth(),
          keyboardOptions=KeyboardOptions(keyboardType=if(field=="name") KeyboardType.Text else KeyboardType.Decimal))
    }
    Button(onClick=onSave,enabled=enabled,modifier=Modifier.fillMaxWidth()) { Text("Сохранить") }
    TextButton(onClick=onBack,enabled=enabled) { Text("Отмена") }
  }
}

@Composable
private fun ColumnScope.ProductCard(product:Product,accountId:String,canWrite:Boolean,onEdit:()->Unit,onDelete:()->Unit,onSelect:((Product)->Unit)?) {
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
    Text(product.name,style=MaterialTheme.typography.titleLarge)
    Text(if(product.kind=="COMPOSITE") "Составной продукт" else "Конечный продукт")
    if(product.isDeleted) Text("Продукт удалён из каталога. Он сохранён для истории.")
    Text("На 100 г",style=MaterialTheme.typography.titleMedium)
    Text("Калории: ${DisplayNumbers.nutrition(product.nutritionPer100g.calories)} ккал")
    Text("Белки: ${DisplayNumbers.nutrition(product.nutritionPer100g.protein)} г")
    Text("Жиры: ${DisplayNumbers.nutrition(product.nutritionPer100g.fat)} г")
    Text("Углеводы: ${DisplayNumbers.nutrition(product.nutritionPer100g.carbs)} г")
    if(onSelect!=null && !product.isDeleted) Button(onClick={ onSelect(product) }) { Text("Выбрать продукт") }
    if(product.canEditLeaf(accountId)) OutlinedButton(onClick=onEdit,enabled=canWrite) { Text("Изменить") }
    if(product.canDelete(accountId)) TextButton(onClick=onDelete,enabled=canWrite) { Text("Удалить из каталога") }
  }
}
@Composable private fun ErrorText(error:ApiError) {
  Text(error.message(),color=MaterialTheme.colorScheme.error)
  error.correlationId?.let { Text("Код обращения: $it",style=MaterialTheme.typography.bodySmall) }
}
