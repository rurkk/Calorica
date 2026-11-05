package com.rurkk.calorica.features.products

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rurkk.calorica.core.api.Product

@Composable
internal fun ColumnScope.CompositionForm(draft: CompositionDraft, model: ProductsViewModel, enabled: Boolean,
    resolving: Boolean, onChoose: () -> Unit) {
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
    Text(if(draft.original==null) "Новый состав" else "Состав: ${draft.original.name}",style=MaterialTheme.typography.titleLarge)
    if(draft.original==null) CompositionInput("Название состава",draft.name,draft.errors["name"],enabled,decimal=false) {
      model.updateComposition { form -> form.copy(name=it) }
    }
    Text("Исходные доли ингредиентов в процентах. Сумма должна быть ровно 100%.")
    draft.ingredients.forEach { item ->
      OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
          Text(item.name,style=MaterialTheme.typography.titleMedium)
          CompositionInput("Доля, %",item.percent,draft.errors[item.productId],enabled) { model.changePercent(item.productId,it) }
          TextButton(onClick={ model.removeComponent(item.productId) },enabled=enabled) { Text("Убрать ингредиент") }
        }
      }
    }
    Text("Сумма: ${draft.totalPercent?.stripTrailingZeros()?.toPlainString() ?: "—"}%")
    draft.errors["total"]?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    draft.errors["components"]?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    if(resolving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Проверяем ингредиент…") }
    OutlinedButton(onClick=onChoose,enabled=enabled && draft.ingredients.size<100) { Text("Добавить ингредиент") }
    if(draft.needsLeafNutrition) {
      Text("Без ингредиентов продукт станет конечным. Укажите ручные КБЖУ на 100 г.")
      val input=draft.leafNutrition
      for((field,label,value) in listOf(Triple("calories","Калории, ккал",input.calories),Triple("protein","Белки, г",input.protein),
          Triple("fat","Жиры, г",input.fat),Triple("carbs","Углеводы, г",input.carbs))) {
        CompositionInput(label,value,draft.errors[field],enabled) { text -> model.updateComposition { form ->
          form.copy(leafNutrition=when(field) {
            "calories" -> form.leafNutrition.copy(calories=text)
            "protein" -> form.leafNutrition.copy(protein=text)
            "fat" -> form.leafNutrition.copy(fat=text)
            else -> form.leafNutrition.copy(carbs=text)
          })
        } }
      }
    }
    if(draft.ingredients.isNotEmpty()) {
      TextButton(onClick={ model.updateComposition { it.copy(yieldExpanded=!it.yieldExpanded) } },enabled=enabled) {
        Text(if(draft.yieldExpanded) "Скрыть настройку выхода" else "Настроить общий выход (необязательно)")
      }
      if(draft.yieldExpanded) {
        draft.original?.let { YieldSummary(it); Text("Показано последнее сохранённое значение. После сохранения сервер обновит выход и КБЖУ.") }
        if(draft.ingredientYield) Text("Выход задан по ингредиентам. Общий коэффициент доступен только для просмотра; настройки ингредиентов сохранятся.")
        else {
          Text("Без настройки коэффициент равен 1. Например, 0,8 означает 80% от исходного веса.")
          CompositionInput("Общий коэффициент выхода",draft.manualYield,draft.errors["yield"],enabled) { value ->
            model.updateComposition { it.copy(manualYield=value,yieldEdited=true) }
          }
          TextButton(onClick={ model.updateComposition { it.copy(manualYield="",yieldEdited=true) } },enabled=enabled) { Text("Убрать настройку выхода") }
        }
      }
      // The source remains visible even while the optional editor is collapsed.
      if(draft.ingredientYield && !draft.yieldExpanded) Text("Выход по ингредиентам — только просмотр.")
    }
    Button(onClick=model::saveComposition,enabled=enabled,modifier=Modifier.fillMaxWidth()) { Text("Сохранить состав") }
    TextButton(onClick=model::back,enabled=enabled) { Text("Отмена") }
  }
}

@Composable
private fun CompositionInput(label: String,value: String,error: String?,enabled: Boolean,decimal: Boolean=true,onChange: (String)->Unit) {
  OutlinedTextField(value=value,onValueChange=onChange,label={ Text(label) },singleLine=true,enabled=enabled,
      isError=error!=null,supportingText={ error?.let { Text(it) } },modifier=Modifier.fillMaxWidth(),
      keyboardOptions=KeyboardOptions(keyboardType=if(decimal) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
internal fun YieldSummary(product: Product) {
  val source=when(product.yieldSource) { "INGREDIENTS" -> "по ингредиентам"; "MANUAL" -> "общий коэффициент"; else -> "по умолчанию" }
  Text("Выход: ${product.effectiveYieldFactor.stripTrailingZeros().toPlainString()} · $source")
}
