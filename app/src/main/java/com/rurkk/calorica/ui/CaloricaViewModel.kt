package com.rurkk.calorica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rurkk.calorica.data.CaloricaRepository
import com.rurkk.calorica.data.FoodEntity
import com.rurkk.calorica.data.GoalEntity
import com.rurkk.calorica.data.MealEntryEntity
import com.rurkk.calorica.data.WeightEntryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayState(
    val foods: List<FoodEntity> = emptyList(),
    val entries: List<MealEntryEntity> = emptyList(),
    val goal: GoalEntity = GoalEntity(calories = 2000, protein = 120, fat = 65, carbs = 230),
    val weights: List<WeightEntryEntity> = emptyList(),
) {
  val calories = entries.sumOf { it.calories }
  val protein = entries.sumOf { it.protein }
  val fat = entries.sumOf { it.fat }
  val carbs = entries.sumOf { it.carbs }
}

@HiltViewModel
class CaloricaViewModel @Inject constructor(private val repository: CaloricaRepository) : ViewModel() {
  private val today = LocalDate.now()

  val state: StateFlow<TodayState> =
      combine(repository.foods(), repository.entries(today), repository.goal(), repository.weights()) {
          foods,
          entries,
          goal,
          weights,
          ->
        TodayState(foods = foods, entries = entries, goal = goal ?: TodayState().goal, weights = weights)
      }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

  init {
    viewModelScope.launch { repository.seedIfNeeded() }
  }

  fun addMeal(food: FoodEntity, grams: Int, meal: String) {
    if (grams <= 0) return
    viewModelScope.launch { repository.addEntry(food, grams, meal, today) }
  }

  fun removeMeal(entryId: String) {
    viewModelScope.launch { repository.deleteEntry(entryId) }
  }

  fun updateMealAmount(entry: MealEntryEntity, grams: Int) {
    viewModelScope.launch { repository.updateEntryAmount(entry, grams) }
  }

  fun addCustomFood(name: String, calories: Int, protein: Double, fat: Double, carbs: Double) {
    if (name.isBlank() || calories < 0 || protein < 0 || fat < 0 || carbs < 0) return
    viewModelScope.launch { repository.addCustomFood(name, calories, protein, fat, carbs) }
  }

  fun saveGoal(calories: Int, protein: Int, fat: Int, carbs: Int) {
    if (calories <= 0 || protein < 0 || fat < 0 || carbs < 0) return
    viewModelScope.launch { repository.updateGoal(calories, protein, fat, carbs) }
  }

  fun addWeight(kilograms: Double) {
    if (kilograms <= 0) return
    viewModelScope.launch { repository.addWeight(kilograms, today) }
  }
}
