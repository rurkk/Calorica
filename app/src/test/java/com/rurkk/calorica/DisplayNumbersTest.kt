package com.rurkk.calorica

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.ui.DisplayNumbers
import com.rurkk.calorica.features.products.ProductDraft
import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DisplayNumbersTest {
  @Test fun productNutritionRoundsHalfUpToAtMostTwoPlaces() {
    assertEquals("123.46", DisplayNumbers.nutrition(BigDecimal("123.456789012345")))
    assertEquals("1.01", DisplayNumbers.nutrition(BigDecimal("1.005")))
    assertEquals("1", DisplayNumbers.nutrition(BigDecimal("1.0049")))
    assertEquals("12.3", DisplayNumbers.nutrition(BigDecimal("12.300000")))
    assertEquals("0", DisplayNumbers.nutrition(BigDecimal("0.004")))
  }

  @Test fun diaryCaloriesAreWholeWhileMacronutrientsRetainTwoPlaces() {
    val nutrition = Nutrition(BigDecimal("78.5"), BigDecimal("0.455"), BigDecimal("0.300"), BigDecimal("21.004"))
    assertEquals("79", DisplayNumbers.totalCalories(nutrition.calories))
    assertEquals("0.46", DisplayNumbers.nutrition(nutrition.protein))
    assertEquals("0.3", DisplayNumbers.nutrition(nutrition.fat))
    assertEquals("21", DisplayNumbers.nutrition(nutrition.carbs))
    assertEquals("78", DisplayNumbers.totalCalories(BigDecimal("78.499999999")))
    assertEquals("0", DisplayNumbers.totalCalories(BigDecimal.ZERO))
  }

  @Test fun massKeepsThreePlacesAndPlainNotationWithoutTrailingZeros() {
    assertEquals("12.346", DisplayNumbers.mass(BigDecimal("12.3455")))
    assertEquals("12.345", DisplayNumbers.mass(BigDecimal("12.3454")))
    assertEquals("200", DisplayNumbers.mass(BigDecimal("200.000")))
    assertEquals("0.001", DisplayNumbers.mass(BigDecimal("0.001")))
    assertEquals("100000000", DisplayNumbers.nutrition(BigDecimal("1E+8")))
    assertEquals("0", DisplayNumbers.mass(BigDecimal("0.000")))
  }

  @Test fun formattingUsesTheExactServerTotalAndDoesNotChangeApiDecimals() {
    // Two 0.49 kcal rows display as zero; the server's unrounded 0.98 kcal total displays as one.
    val row = Nutrition(BigDecimal("0.49"), BigDecimal("0.0049"), BigDecimal.ZERO, BigDecimal.ZERO)
    val total = Nutrition(BigDecimal("0.98"), BigDecimal("0.0098"), BigDecimal.ZERO, BigDecimal.ZERO)
    val original = ApiJson.encodeToString(total)
    assertEquals("0", DisplayNumbers.totalCalories(row.calories))
    assertEquals("1", DisplayNumbers.totalCalories(total.calories))
    assertEquals("0.01", DisplayNumbers.nutrition(total.protein))
    assertEquals(original, ApiJson.encodeToString(total))
    assertEquals(BigDecimal("0.98"), total.calories)
    assertEquals(BigDecimal("0.0098"), total.protein)
  }

  @Test fun roundedDisplayDoesNotReplaceExactValuesInTheProductEditor() {
    val manual = NutritionInputSet(BigDecimal("78.55"), BigDecimal("0.45"), BigDecimal("0.30"), BigDecimal("21.04"))
    val product = Product("product", "owner", "Продукт", false, "LEAF", manual,
        Nutrition(manual.calories, manual.protein, manual.fat, manual.carbs), emptyList(), null,
        BigDecimal.ONE, "DEFAULT", emptyList())
    assertEquals("79", DisplayNumbers.totalCalories(product.nutritionPer100g.calories))
    assertEquals("0.3", DisplayNumbers.nutrition(product.nutritionPer100g.fat))
    val draft = ProductDraft.from(product)
    assertEquals("78.55", draft.calories)
    assertEquals("0.30", draft.fat)
    assertEquals(manual, draft.nutrition())
    assertTrue(draft.patch().json().isEmpty())
  }
}
