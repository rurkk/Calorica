package com.rurkk.calorica.features.products

import com.rurkk.calorica.core.api.*
import java.math.BigDecimal

/** Only editable leaf fields: composition/yield/labels can never be cleared by this form. */
data class ProductDraft(
    val original: Product? = null, val name: String = "", val calories: String = "",
    val protein: String = "", val fat: String = "", val carbs: String = "",
    val errors: Map<String,String> = emptyMap(),
) {
  fun validationErrors(): Map<String,String> = buildMap {
    if (name.trim().codePointCount(0,name.trim().length) !in 1..200) put("name", "Введите название от 1 до 200 символов.")
    for ((field,value) in listOf("calories" to calories,"protein" to protein,"fat" to fat,"carbs" to carbs)) {
      if (number(value) == null) put(field, "Введите число от 0 до 99999,99, до двух знаков после запятой.")
    }
  }
  fun nutrition(): NutritionInputSet {
    require(validationErrors().isEmpty())
    return NutritionInputSet(requireNotNull(number(calories)),requireNotNull(number(protein)),
        requireNotNull(number(fat)),requireNotNull(number(carbs)))
  }
  fun patch(): ProductPatch {
    val previous = requireNotNull(original)
    require(previous.kind == "LEAF" && !previous.isDeleted)
    val values = nutrition()
    return ProductPatch(name = if (name.trim() == previous.name) PatchField.Unchanged else PatchField.Set(name.trim()),
        manualNutrition = if (sameNutrition(values, previous.manualNutrition)) PatchField.Unchanged else PatchField.Set(values))
  }
  companion object {
    fun from(product: Product): ProductDraft {
      require(product.kind == "LEAF")
      val nutrition = requireNotNull(product.manualNutrition)
      return ProductDraft(product,product.name,nutrition.calories.toPlainString(),nutrition.protein.toPlainString(),
          nutrition.fat.toPlainString(),nutrition.carbs.toPlainString())
    }
    private fun number(input: String): BigDecimal? {
      val normalized = input.trim().replace(',', '.')
      if (normalized.length > 9 || !Regex("(0|[1-9][0-9]*)(\\.[0-9]{1,2})?").matches(normalized)) return null
      return normalized.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it <= BigDecimal("99999.99") }
    }
    private fun sameNutrition(a: NutritionInputSet, b: NutritionInputSet?) = b != null &&
        a.calories.compareTo(b.calories) == 0 && a.protein.compareTo(b.protein) == 0 &&
        a.fat.compareTo(b.fat) == 0 && a.carbs.compareTo(b.carbs) == 0
  }
}
fun Product.canEditLeaf(accountId: String) = ownerId == accountId && !isDeleted && kind == "LEAF" && manualNutrition != null
fun Product.canDelete(accountId: String) = ownerId == accountId && !isDeleted
