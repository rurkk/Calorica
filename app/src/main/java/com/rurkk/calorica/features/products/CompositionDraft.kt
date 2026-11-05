package com.rurkk.calorica.features.products

import com.rurkk.calorica.core.api.*
import java.math.BigDecimal

/** Exact editable inputs only. Nutrition and effective yield are always supplied by the server. */
data class CompositionIngredient(val productId: String, val name: String, val percent: String)

data class CompositionDraft(
    val original: Product? = null,
    val name: String = "",
    val ingredients: List<CompositionIngredient> = emptyList(),
    val manualYield: String = "",
    val yieldEdited: Boolean = false,
    val yieldExpanded: Boolean = false,
    val leafNutrition: ProductDraft = ProductDraft(),
    val errors: Map<String, String> = emptyMap(),
) {
  // An omitted coefficient is matched by child ID on the server, including remove + re-add.
  val ingredientYield: Boolean get() = ingredients.any { item ->
    original?.components?.any { it.productId == item.productId && it.yieldFactor != null } == true
  }
  val needsLeafNutrition: Boolean get() = original != null && ingredients.isEmpty()
  val totalPercent: BigDecimal? get() {
    val values = ingredients.map { percent(it.percent) ?: return null }
    return values.fold(BigDecimal.ZERO, BigDecimal::add)
  }

  fun validationErrors(): Map<String, String> = buildMap {
    if (original == null) {
      val title = name.trim()
      if (title.codePointCount(0, title.length) !in 1..200) put("name", "Введите название от 1 до 200 символов.")
      if (ingredients.isEmpty()) put("components", "Добавьте хотя бы один ингредиент.")
    }
    if (ingredients.size > 100) put("components", "Допускается не более 100 ингредиентов.")
    if (ingredients.map { it.productId }.distinct().size != ingredients.size) put("components", "Ингредиент уже добавлен.")
    ingredients.forEach { if (percent(it.percent) == null) put(it.productId, "Введите процент больше 0 и не больше 100, до 10 знаков после запятой.") }
    if (ingredients.isNotEmpty() && totalPercent?.compareTo(BigDecimal("100")) != 0) put("total", "Сумма должна быть ровно 100%.")
    if (ingredients.isNotEmpty() && !ingredientYield && yieldEdited && manualYield.isNotBlank() && factor(manualYield) == null) {
      put("yield", "Введите коэффициент больше 0 и меньше 10000, до 12 знаков после запятой.")
    }
    if (needsLeafNutrition) putAll(leafNutrition.validationErrors().filterKeys { it != "name" })
  }

  fun request(): CompositionWrite.Percent {
    require(validationErrors().isEmpty())
    val output: PatchField<Decimal> = when {
      ingredients.isEmpty() || ingredientYield || !yieldEdited -> PatchField.Unchanged
      manualYield.isBlank() -> PatchField.Clear
      else -> PatchField.Set(requireNotNull(factor(manualYield)))
    }
    val nutrition: PatchField<NutritionInputSet> = if (needsLeafNutrition) {
      val value = leafNutrition.copy(name = original!!.name).nutrition()
      if (value.sameValues(original.manualNutrition)) PatchField.Unchanged else PatchField.Set(value)
    } else PatchField.Unchanged
    return CompositionWrite.Percent(ingredients.map { PercentComponent(it.productId, requireNotNull(percent(it.percent))) }, output, nutrition)
  }

  companion object {
    fun from(product: Product) = CompositionDraft(
        original = product, name = product.name,
        ingredients = product.components.map { CompositionIngredient(it.productId, "Ингредиент ${it.productId}", it.fraction.toExactPercent().stripTrailingZeros().toPlainString()) },
        manualYield = product.manualYieldFactor?.toPlainString().orEmpty(),
        leafNutrition = ProductDraft.fromManualNutrition(product),
    )
    private fun percent(input: String) = decimal(input, Regex("(0|[1-9][0-9]?|100)(\\.[0-9]{1,10})?"))
        ?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("100") }
    private fun factor(input: String) = decimal(input, Regex("(0|[1-9][0-9]{0,3})(\\.[0-9]{1,12})?"))
        ?.takeIf { it > BigDecimal.ZERO && it < BigDecimal("10000") }
    private fun decimal(input: String, syntax: Regex): BigDecimal? {
      val value = input.trim().replace(',', '.')
      return if (value.length <= 17 && syntax.matches(value)) value.toBigDecimalOrNull() else null
    }
  }
}

internal fun NutritionInputSet.sameValues(other: NutritionInputSet?) = other != null &&
    calories.compareTo(other.calories) == 0 && protein.compareTo(other.protein) == 0 &&
    fat.compareTo(other.fat) == 0 && carbs.compareTo(other.carbs) == 0
