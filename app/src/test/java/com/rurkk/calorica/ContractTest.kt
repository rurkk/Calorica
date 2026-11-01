package com.rurkk.calorica

import com.rurkk.calorica.core.api.*
import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
  private val fixtures = ApiJson.parseToJsonElement(requireNotNull(javaClass.getResource("/fixtures.json")).readText()).jsonArray
  private fun fixture(name: String) = fixtures.first { it.jsonObject["name"]?.jsonPrimitive?.content == name }.jsonObject

  @Test fun frozenResponsesDecodeWithoutLosingDecimalPrecision() {
    val product = ApiJson.decodeFromJsonElement<Product>(fixture("composition").getValue("response"))
    assertEquals(BigDecimal("240"), product.nutritionPer100g.calories)
    assertNull(product.manualNutrition)
    assertEquals(BigDecimal("0.5"), product.components[0].fraction)
    val tokens = ApiJson.decodeFromJsonElement<Tokens>(fixture("register").getValue("response"))
    assertNull(tokens.account.profile.calorieTarget)
    assertFalse(tokens.toString().contains(tokens.accessToken))
    val page = ApiJson.decodeFromJsonElement<ProductPage>(fixture("search").getValue("response"))
    assertEquals(2, page.items.size)
    val diary = ApiJson.decodeFromJsonElement<DiaryDay>(fixture("diary-day").getValue("response"))
    assertEquals(BigDecimal("480"), diary.total.calories)
    val problem = ApiJson.decodeFromJsonElement<Problem>(fixture("invalid-percent").getValue("response"))
    assertEquals("PERCENT_SUM", problem.errors.single().code)
  }
  @Test fun preciseFractionsAndCalculatedValuesRoundTripAsStrings() {
    for (value in listOf("0.333333333334", "0.333333333333", "123456789012345678901234567890.12345678901234567890")) {
      val decimal = DecimalSerializer.parse(value)
      assertEquals(value, decimal.toExactPercent().toExactFraction().toPlainString())
      assertEquals(JsonPrimitive(value), ApiJson.encodeToJsonElement(DecimalSerializer, decimal))
      assertEquals(decimal, ApiJson.decodeFromJsonElement(DecimalSerializer, JsonPrimitive(value)))
    }
  }
  @Test fun decimalRejectsBinaryNumbersExponentPlusNanAndOversizedValues() {
    for (json in listOf("0.1", "\"1e-3\"", "\"+1\"", "\"NaN\"", "\"Infinity\"", "\"01\"", "\"${"1".repeat(1104)}\"")) {
      assertThrows(SerializationException::class.java) { ApiJson.decodeFromString(DecimalSerializer, json) }
    }
  }
  @Test fun missingRequiredResponseIsNotAnEmptySuccess() {
    assertThrows(SerializationException::class.java) { ApiJson.decodeFromString<ProductPage>("{}") }
    assertThrows(SerializationException::class.java) { ApiJson.decodeFromString<DiaryDay>("{}") }
  }
  @Test fun patchDistinguishesAbsentClearSetAndRejectsIllegalClear() {
    assertEquals(buildJsonObject {}, ProductPatch().json())
    val unchanged = CompositionWrite.Percent(listOf(PercentComponent("id", BigDecimal("33.3333333334")))).json()
    assertFalse(unchanged.containsKey("manualYieldFactor"))
    assertFalse(unchanged["components"]!!.jsonArray.single().jsonObject.containsKey("yieldFactor"))
    val clear = CompositionWrite.Percent(listOf(PercentComponent("id", BigDecimal("100"), PatchField.Clear)), PatchField.Clear).json()
    assertEquals(JsonNull, clear["manualYieldFactor"])
    assertEquals(JsonNull, clear["components"]!!.jsonArray.single().jsonObject["yieldFactor"])
    assertEquals(JsonArray(emptyList()), DiaryPatch(mealLabelIds = PatchField.Set(emptyList())).json()["mealLabelIds"])
    assertThrows(IllegalArgumentException::class.java) { ProductPatch(name = PatchField.Clear).json() }
    assertThrows(IllegalArgumentException::class.java) { DiaryPatch(quantity = PatchField.Set(BigDecimal.ONE)).json() }
    assertEquals(JsonPrimitive("1.000"), DiaryPatch(quantity = PatchField.Set(BigDecimal("1.000")), unit = PatchField.Set("g")).json()["quantity"])
  }
  @Test fun writesMatchFrozenRequestsAndNeverInventNutrition() {
    val components = listOf(PercentComponent("00000000-0000-4000-8000-000000000001", BigDecimal("50"), PatchField.Set(BigDecimal("0.5"))),
        PercentComponent("00000000-0000-4000-8000-000000000002", BigDecimal("50"), PatchField.Set(BigDecimal("1.5"))))
    val composition = CompositionWrite.Percent(components, PatchField.Clear)
    assertEquals(fixture("composition")["request"], composition.json())
    assertEquals(fixture("create-composite")["request"], ProductCreate.Composite("Контрольный состав", composition).json())
    val diary = ApiJson.decodeFromJsonElement<DiaryWrite>(fixture("diary-create").getValue("request"))
    assertEquals(fixture("diary-create")["request"], ApiJson.encodeToJsonElement(diary))
  }
}
