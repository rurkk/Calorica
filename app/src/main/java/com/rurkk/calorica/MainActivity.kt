package com.rurkk.calorica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rurkk.calorica.data.FoodEntity
import com.rurkk.calorica.data.MealEntryEntity
import com.rurkk.calorica.ui.CaloricaViewModel
import com.rurkk.calorica.ui.TodayState
import com.rurkk.calorica.ui.theme.CaloricaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { CaloricaTheme { CaloricaApp() } }
  }
}

private enum class AppTab(val label: String) {
  Diary("Дневник"),
  Weight("Вес"),
  Settings("Цели"),
}

@Composable
private fun CaloricaApp(viewModel: CaloricaViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsState()
  var tab by remember { mutableStateOf(AppTab.Diary) }
  var addingMeal by remember { mutableStateOf(false) }
  var creatingFood by remember { mutableStateOf(false) }
  var editingEntry by remember { mutableStateOf<MealEntryEntity?>(null) }

  Scaffold(
      topBar = { CaloricaTopBar(tab) },
      bottomBar = { CaloricaNavigation(tab, onSelected = { tab = it }) },
      floatingActionButton = {
        if (tab == AppTab.Diary) {
          FloatingActionButton(onClick = { addingMeal = true }) {
            Icon(Icons.Default.Add, contentDescription = "Добавить еду")
          }
        }
      },
  ) { padding ->
    when (tab) {
      AppTab.Diary -> DiaryScreen(state, onEdit = { editingEntry = it }, onDelete = viewModel::removeMeal, modifier = Modifier.padding(padding))
      AppTab.Weight -> WeightScreen(state, viewModel::addWeight, Modifier.padding(padding))
      AppTab.Settings -> GoalScreen(state, viewModel::saveGoal, Modifier.padding(padding))
    }
  }
  if (addingMeal) {
    AddMealSheet(
        foods = state.foods,
        onDismiss = { addingMeal = false },
        onAdd = { food, grams, meal ->
          viewModel.addMeal(food, grams, meal)
          addingMeal = false
        },
        onCreateFood = {
          addingMeal = false
          creatingFood = true
        },
    )
  }
  if (creatingFood) {
    CustomFoodDialog(
        onDismiss = { creatingFood = false },
        onConfirm = { name, calories, protein, fat, carbs ->
          viewModel.addCustomFood(name, calories, protein, fat, carbs)
          creatingFood = false
        },
    )
  }
  editingEntry?.let { entry ->
    EditMealDialog(
        entry = entry,
        onDismiss = { editingEntry = null },
        onConfirm = { grams ->
          viewModel.updateMealAmount(entry, grams)
          editingEntry = null
        },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaloricaTopBar(tab: AppTab) {
  CenterAlignedTopAppBar(
      title = { Text(if (tab == AppTab.Diary) "Сегодня" else tab.label) },
      colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
  )
}

@Composable
private fun CaloricaNavigation(selected: AppTab, onSelected: (AppTab) -> Unit) {
  NavigationBar {
    NavigationBarItem(
        selected = selected == AppTab.Diary,
        onClick = { onSelected(AppTab.Diary) },
        icon = { Icon(Icons.Default.Restaurant, contentDescription = null) },
        label = { Text(AppTab.Diary.label) },
    )
    NavigationBarItem(
        selected = selected == AppTab.Weight,
        onClick = { onSelected(AppTab.Weight) },
        icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
        label = { Text(AppTab.Weight.label) },
    )
    NavigationBarItem(
        selected = selected == AppTab.Settings,
        onClick = { onSelected(AppTab.Settings) },
        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
        label = { Text(AppTab.Settings.label) },
    )
  }
}

@Composable
private fun DiaryScreen(
    state: TodayState,
    onEdit: (MealEntryEntity) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { DailySummary(state) }
    if (state.entries.isEmpty()) {
      item {
        Card {
          Text(
              "Здесь появится ваш дневник. Добавьте первый продукт — итоги дня обновятся сразу.",
              modifier = Modifier.padding(20.dp),
          )
        }
      }
    } else {
      items(state.entries, key = { it.id }) { entry -> MealRow(entry, onEdit, onDelete) }
    }
  }
}

@Composable
private fun DailySummary(state: TodayState) {
  val remaining = state.goal.calories - state.calories
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Калории", style = MaterialTheme.typography.titleMedium)
      Text("${state.calories} из ${state.goal.calories} ккал", style = MaterialTheme.typography.headlineMedium)
      Text(
          if (remaining >= 0) "Осталось $remaining ккал" else "На сегодня на ${-remaining} ккал больше цели",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      HorizontalDivider()
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Macro("Белки", state.protein, state.goal.protein)
        Macro("Жиры", state.fat, state.goal.fat)
        Macro("Углеводы", state.carbs, state.goal.carbs)
      }
    }
  }
}

@Composable
private fun Macro(label: String, actual: Double, goal: Int) {
  Column {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text("${actual.roundToInt()} / $goal г", style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun MealRow(entry: MealEntryEntity, onEdit: (MealEntryEntity) -> Unit, onDelete: (String) -> Unit) {
  ListItem(
      headlineContent = { Text(entry.foodName) },
      supportingContent = { Text("${entry.meal} · ${entry.grams} г · Б ${entry.protein.roundToInt()} · Ж ${entry.fat.roundToInt()} · У ${entry.carbs.roundToInt()}") },
      trailingContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("${entry.calories} ккал")
          IconButton(onClick = { onEdit(entry) }) {
            Icon(Icons.Default.Edit, contentDescription = "Изменить ${entry.foodName}")
          }
          IconButton(onClick = { onDelete(entry.id) }) {
            Icon(Icons.Default.Delete, contentDescription = "Удалить ${entry.foodName}")
          }
        }
      },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMealSheet(
    foods: List<FoodEntity>,
    onDismiss: () -> Unit,
    onAdd: (FoodEntity, Int, String) -> Unit,
    onCreateFood: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  var selectedFood by remember { mutableStateOf<FoodEntity?>(null) }
  var grams by remember { mutableStateOf("100") }
  var meal by remember { mutableStateOf("Завтрак") }
  val filtered = foods.filter { it.name.contains(query, ignoreCase = true) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Добавить еду", style = MaterialTheme.typography.headlineSmall)
      OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          label = { Text("Поиск продукта") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
      )
      if (selectedFood == null) {
        LazyColumn(modifier = Modifier.height(230.dp)) {
          items(filtered, key = { it.id }) { food ->
            ListItem(
                headlineContent = { Text(food.name) },
                supportingContent = { Text("${food.caloriesPer100g} ккал на 100 г") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { selectedFood = food }) { Text("Выбрать") }
          }
        }
        TextButton(onClick = onCreateFood, modifier = Modifier.align(Alignment.End)) {
          Text("Создать свой продукт")
        }
      } else {
        Text("Выбрано: ${selectedFood!!.name}")
        OutlinedTextField(
            value = grams,
            onValueChange = { grams = it.filter(Char::isDigit) },
            label = { Text("Масса, г") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("Завтрак", "Обед", "Ужин", "Перекус").forEach { option ->
            if (meal == option) Button(onClick = { meal = option }) { Text(option) }
            else OutlinedButton(onClick = { meal = option }) { Text(option) }
          }
        }
        Button(
            onClick = { onAdd(selectedFood!!, grams.toIntOrNull() ?: 0, meal) },
            enabled = (grams.toIntOrNull() ?: 0) > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Добавить в дневник") }
        TextButton(onClick = { selectedFood = null }) { Text("Выбрать другой продукт") }
      }
      Spacer(Modifier.height(18.dp))
    }
  }
}

@Composable
private fun EditMealDialog(entry: MealEntryEntity, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
  var grams by remember { mutableStateOf(entry.grams.toString()) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Изменить количество") },
      text = {
        OutlinedTextField(
            value = grams,
            onValueChange = { grams = it.filter(Char::isDigit) },
            label = { Text("Масса, г") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
      },
      confirmButton = {
        TextButton(onClick = { grams.toIntOrNull()?.takeIf { it > 0 }?.let(onConfirm) }) {
          Text("Сохранить")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

@Composable
private fun CustomFoodDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Double, Double, Double) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var calories by remember { mutableStateOf("") }
  var protein by remember { mutableStateOf("") }
  var fat by remember { mutableStateOf("") }
  var carbs by remember { mutableStateOf("") }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Свой продукт") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
          NutrientField("Калории на 100 г", calories) { calories = it }
          NutrientField("Белки на 100 г", protein) { protein = it }
          NutrientField("Жиры на 100 г", fat) { fat = it }
          NutrientField("Углеводы на 100 г", carbs) { carbs = it }
        }
      },
      confirmButton = {
        TextButton(
            onClick = {
              val parsedProtein = protein.replace(',', '.').toDoubleOrNull()
              val parsedFat = fat.replace(',', '.').toDoubleOrNull()
              val parsedCarbs = carbs.replace(',', '.').toDoubleOrNull()
              val parsedCalories = calories.toIntOrNull()
              if (parsedCalories != null && parsedProtein != null && parsedFat != null && parsedCarbs != null) {
                onConfirm(name, parsedCalories, parsedProtein, parsedFat, parsedCarbs)
              }
            },
        ) { Text("Сохранить") }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

@Composable
private fun NutrientField(label: String, value: String, onValueChange: (String) -> Unit) {
  OutlinedTextField(
      value = value,
      onValueChange = { onValueChange(it.filter { char -> char.isDigit() || char == ',' || char == '.' }) },
      label = { Text(label) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      singleLine = true,
  )
}

@Composable
private fun WeightScreen(state: TodayState, onAddWeight: (Double) -> Unit, modifier: Modifier = Modifier) {
  var adding by remember { mutableStateOf(false) }
  LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
          Text("Динамика веса", style = MaterialTheme.typography.titleLarge)
          Text("Измерения помогают видеть тенденцию, а не оценивать отдельный день.")
          Spacer(Modifier.height(12.dp))
          Button(onClick = { adding = true }) { Text("Добавить измерение") }
        }
      }
    }
    if (state.weights.isEmpty()) item { Text("Пока нет измерений.") }
    items(state.weights, key = { it.id }) { weight ->
      ListItem(headlineContent = { Text("${weight.kilograms} кг") }, supportingContent = { Text(weight.date) })
    }
  }
  if (adding) WeightDialog(onDismiss = { adding = false }, onConfirm = { onAddWeight(it); adding = false })
}

@Composable
private fun WeightDialog(onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
  var value by remember { mutableStateOf("") }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Вес сегодня") },
      text = {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.filter { char -> char.isDigit() || char == ',' || char == '.' } },
            label = { Text("Килограммы") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
      },
      confirmButton = { TextButton(onClick = { value.replace(',', '.').toDoubleOrNull()?.let(onConfirm) }) { Text("Сохранить") } },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

@Composable
private fun GoalScreen(state: TodayState, onSave: (Int, Int, Int, Int) -> Unit, modifier: Modifier = Modifier) {
  var calories by remember(state.goal) { mutableStateOf(state.goal.calories.toString()) }
  var protein by remember(state.goal) { mutableStateOf(state.goal.protein.toString()) }
  var fat by remember(state.goal) { mutableStateOf(state.goal.fat.toString()) }
  var carbs by remember(state.goal) { mutableStateOf(state.goal.carbs.toString()) }
  Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Дневные ориентиры", style = MaterialTheme.typography.titleLarge)
    Text("Это личные ориентиры, а не медицинское назначение. Их можно менять в любой момент.")
    GoalField("Калории, ккал", calories) { calories = it }
    GoalField("Белки, г", protein) { protein = it }
    GoalField("Жиры, г", fat) { fat = it }
    GoalField("Углеводы, г", carbs) { carbs = it }
    Button(
        onClick = { onSave(calories.toIntOrNull() ?: 0, protein.toIntOrNull() ?: -1, fat.toIntOrNull() ?: -1, carbs.toIntOrNull() ?: -1) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Сохранить ориентиры") }
  }
}

@Composable
private fun GoalField(label: String, value: String, onValueChange: (String) -> Unit) {
  OutlinedTextField(
      value = value,
      onValueChange = { onValueChange(it.filter(Char::isDigit)) },
      label = { Text(label) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
  )
}
