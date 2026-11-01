package com.rurkk.calorica.core.api

import java.math.BigDecimal
import kotlinx.serialization.json.*

/** Absent and explicit null must survive a round trip independently. */
sealed interface PatchField<out T> {
  data object Unchanged : PatchField<Nothing>
  data class Set<T>(val value: T) : PatchField<T>
  data object Clear : PatchField<Nothing>
}

internal inline fun <reified T> JsonObjectBuilder.field(name: String, field: PatchField<T>, nullable: Boolean = false) {
  when (field) {
    PatchField.Unchanged -> Unit
    PatchField.Clear -> { require(nullable) { "$name cannot be cleared" }; put(name, JsonNull) }
    is PatchField.Set -> {
      // Reified inference erases the Decimal typealias annotation: encode directly.
      val value = field.value
      put(name, if (value is BigDecimal) JsonPrimitive(value.toPlainString()) else ApiJson.encodeToJsonElement(value))
    }
  }
}

data class ProductPatch(
    val name: PatchField<String> = PatchField.Unchanged,
    val manualNutrition: PatchField<NutritionInputSet> = PatchField.Unchanged,
) {
  fun json() = buildJsonObject { field("name", name); field("manualNutrition", manualNutrition) }
}

data class DiaryPatch(
    val productId: PatchField<String> = PatchField.Unchanged,
    val quantity: PatchField<Decimal> = PatchField.Unchanged,
    val unit: PatchField<String> = PatchField.Unchanged,
    val occurredAt: PatchField<String> = PatchField.Unchanged,
    val mealLabelIds: PatchField<List<String>> = PatchField.Unchanged,
) {
  fun json() = buildJsonObject {
    require((quantity is PatchField.Set) == (unit is PatchField.Set)) { "quantity and unit must be changed together" }
    field("productId", productId); field("quantity", quantity); field("unit", unit)
    field("occurredAt", occurredAt); field("mealLabelIds", mealLabelIds)
  }
}

data class PercentComponent(val productId: String, val percent: Decimal, val yieldFactor: PatchField<Decimal> = PatchField.Unchanged) {
  fun json() = buildJsonObject { put("productId", productId); put("percent", percent.toPlainString()); field("yieldFactor", yieldFactor, true) }
}
data class GramComponent(val productId: String, val grams: Decimal, val yieldFactor: PatchField<Decimal> = PatchField.Unchanged) {
  fun json() = buildJsonObject { put("productId", productId); put("grams", grams.toPlainString()); field("yieldFactor", yieldFactor, true) }
}
sealed interface CompositionWrite {
  fun json(): JsonObject
  data class Percent(
      val components: List<PercentComponent>, val manualYieldFactor: PatchField<Decimal> = PatchField.Unchanged,
      val manualNutrition: PatchField<NutritionInputSet> = PatchField.Unchanged,
  ) : CompositionWrite {
    override fun json() = buildJsonObject {
      put("inputMode", "PERCENT"); put("components", JsonArray(components.map { it.json() }))
      field("manualYieldFactor", manualYieldFactor, true); field("manualNutrition", manualNutrition)
    }
  }
  data class Grams(
      val components: List<GramComponent>, val manualYieldFactor: PatchField<Decimal> = PatchField.Unchanged,
      val outputGrams: PatchField<Decimal> = PatchField.Unchanged,
  ) : CompositionWrite {
    override fun json() = buildJsonObject {
      put("inputMode", "GRAMS"); put("components", JsonArray(components.map { it.json() }))
      field("manualYieldFactor", manualYieldFactor, true); field("outputGrams", outputGrams)
    }
  }
}
sealed interface ProductCreate {
  fun json(): JsonObject
  data class Leaf(val name: String, val manualNutrition: NutritionInputSet) : ProductCreate {
    override fun json() = buildJsonObject { put("name", name); put("manualNutrition", ApiJson.encodeToJsonElement(manualNutrition)) }
  }
  data class Composite(val name: String, val composition: CompositionWrite) : ProductCreate {
    override fun json() = buildJsonObject { put("name", name); put("composition", composition.json()) }
  }
}
