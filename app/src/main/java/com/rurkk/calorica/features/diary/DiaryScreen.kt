package com.rurkk.calorica.features.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.DiaryRepository
import com.rurkk.calorica.core.data.ProductRepository
import com.rurkk.calorica.core.navigation.ProductPickerHost
import com.rurkk.calorica.core.ui.ReadState
import java.time.*

@Composable
fun DiaryRoute(repository: DiaryRepository, products: ProductRepository, picker: ProductPickerHost) {
  val factory=remember(repository,products) {
    object : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
        require(modelClass == DiaryViewModel::class)
        @Suppress("UNCHECKED_CAST") return DiaryViewModel(repository,products) as T
      }
    }
  }
  val vm: DiaryViewModel=viewModel(factory=factory)
  val state by vm.state.collectAsStateWithLifecycle()
  val owner=LocalLifecycleOwner.current
  LaunchedEffect(vm) { vm.resumeReads() }
  LaunchedEffect(picker.result) { picker.result?.let { vm.acceptProduct(it); picker.consume() } }
  DisposableEffect(vm,owner) {
    val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) vm.resumeReads() else if(event==Lifecycle.Event.ON_PAUSE) vm.pauseReads() }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer); vm.pauseReads() }
  }
  DiaryScreen(state,vm,picker)
}

@Composable
private fun DiaryScreen(state: DiaryState, vm: DiaryViewModel, picker: ProductPickerHost) {
  var date by remember(state.date) { mutableStateOf(state.date.toString()) }
  var dateError by remember { mutableStateOf<String?>(null) }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
    Text("Дневник питания",style=MaterialTheme.typography.headlineSmall)
    Text("Часовой пояс: ${state.zone.id}",style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(value=date,onValueChange={ date=it; dateError=null },label={ Text("День: ГГГГ-ММ-ДД") },
        enabled=!state.busy && !state.uncertain && !state.resolvingProduct,isError=dateError!=null,modifier=Modifier.fillMaxWidth(),singleLine=true)
    dateError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
      Button(enabled=!state.busy && !state.uncertain && !state.resolvingProduct,onClick={
        try { vm.selectDate(LocalDate.parse(date)) } catch (_: DateTimeException) { dateError="Введите дату ГГГГ-ММ-ДД." }
      }) { Text("Открыть день") }
      OutlinedButton(enabled=!state.busy && state.day!=ReadState.Loading,onClick=vm::reload) { Text("Обновить") }
    }
    state.message?.let { Text(it) }
    state.correlationId?.let { Text("Код обращения: $it",style=MaterialTheme.typography.bodySmall) }
    if(state.uncertain) {
      Text("Проверьте записи после обновления. Сохранение автоматически не повторяется.")
      OutlinedButton(onClick=vm::acknowledgeUncertain,enabled=!state.busy &&
          (state.day is ReadState.Data || state.day is ReadState.Empty)) { Text("Записи проверены") }
    }
    when(val day=state.day) {
      ReadState.Idle,ReadState.Loading -> CircularProgressIndicator()
      is ReadState.Error -> { Text(day.error.message(),color=MaterialTheme.colorScheme.error)
        day.error.correlationId?.let { Text("Код обращения: $it") } }
      is ReadState.Empty -> { NutritionText("Итог дня",day.value.total); Text("За этот день записей нет.") }
      is ReadState.Data -> {
        NutritionText("Итог дня",day.value.total)
        day.value.entries.forEach { entry ->
          Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
              Text(entry.productName,style=MaterialTheme.typography.titleMedium)
              if(entry.productDeleted) Text("Продукт удалён из каталога; запись сохранена.")
              Text("${entry.consumedGrams.toPlainString()} г · ${Instant.parse(entry.occurredAt).atZone(state.zone).toLocalTime()}")
              NutritionText("Порция",entry.nutrition)
              val labels=when(val loaded=state.labels) {
                is ReadState.Data -> loaded.value.filter { it.id in entry.mealLabelIds }
                else -> emptyList()
              }
              if(labels.isNotEmpty()) Text(labels.joinToString { it.name })
              Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(enabled=!state.busy && !state.uncertain && !state.resolvingProduct,onClick={ vm.edit(entry) }) { Text("Изменить") }
                TextButton(enabled=!state.busy && !state.uncertain && !state.resolvingProduct,onClick={ vm.delete(entry) }) { Text("Удалить") }
              }
            }
          }
        }
      }
    }
    Button(onClick=vm::newEntry,enabled=!state.busy && !state.uncertain && !state.resolvingProduct) { Text("Добавить запись") }
    state.form?.let { form ->
      HorizontalDivider()
      Text(if(form.id==null) "Новая запись" else "Изменение записи",style=MaterialTheme.typography.titleLarge)
      Text(form.productName.ifEmpty { "Продукт ещё не выбран" })
      if(state.resolvingProduct) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Проверяем выбранный продукт…") }
      OutlinedButton(enabled=!state.busy && !state.resolvingProduct,onClick={ vm.chooseProduct()?.let(picker.launch) }) { Text("Выбрать продукт") }
      OutlinedTextField(value=form.grams,onValueChange={ vm.update { f -> f.copy(grams=it) } },label={ Text("Съедено, г") },
          enabled=!state.busy,singleLine=true,modifier=Modifier.fillMaxWidth())
      OutlinedTextField(value=form.date,onValueChange={ vm.update { f -> f.copy(date=it,offsetSeconds=null) } },
          label={ Text("Дата: ГГГГ-ММ-ДД") },enabled=!state.busy,singleLine=true,modifier=Modifier.fillMaxWidth())
      OutlinedTextField(value=form.time,onValueChange={ vm.update { f -> f.copy(time=it,offsetSeconds=null) } },
          label={ Text("Время: ЧЧ:ММ") },enabled=!state.busy,singleLine=true,modifier=Modifier.fillMaxWidth())
      val offsets=remember(form.date,form.time,state.zone) {
        runCatching { state.zone.rules.getValidOffsets(LocalDateTime.of(LocalDate.parse(form.date),LocalTime.parse(form.time))) }
            .getOrDefault(emptyList())
      }
      if(offsets.size>1) {
        val unchanged=form.originalOccurredAt?.let { runCatching { Instant.parse(it).atZone(state.zone).toLocalDateTime() }.getOrNull() } ==
            runCatching { LocalDateTime.of(LocalDate.parse(form.date),LocalTime.parse(form.time)) }.getOrNull()
        Text(if(unchanged) "Время встречается дважды. Исходный момент записи сохранён."
            else "Из-за перевода часов это время встречается дважды. Будет использовано первое вхождение (${offsets.first()}).")
      }
      when(val labels=state.labels) {
        ReadState.Idle,ReadState.Loading -> Text("Загружаем пищевые лейблы…")
        is ReadState.Error -> { Text(labels.error.message()); TextButton(onClick=vm::loadLabels) { Text("Загрузить лейблы") } }
        is ReadState.Data -> labels.value.forEach { label ->
          FilterChip(selected=label.id in form.mealLabelIds,onClick={ vm.toggleLabel(label.id) },label={ Text(label.name) },
              enabled=!state.busy && (label.id in form.mealLabelIds || form.mealLabelIds.size<4))
        }
        is ReadState.Empty -> Unit
      }
      form.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick=vm::save,enabled=!state.busy && !state.uncertain && !state.resolvingProduct) { Text(if(state.busy) "Сохраняем…" else "Сохранить") }
        OutlinedButton(onClick=vm::closeForm,enabled=!state.busy) { Text("Отмена") }
      }
    }
  }
}

@Composable
private fun NutritionText(title: String, value: Nutrition) {
  Text("$title: ${value.calories.toPlainString()} ккал · Б ${value.protein.toPlainString()} г · Ж ${value.fat.toPlainString()} г · У ${value.carbs.toPlainString()} г")
}
