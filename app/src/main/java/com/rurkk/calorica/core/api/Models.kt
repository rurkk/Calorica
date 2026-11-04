package com.rurkk.calorica.core.api

import kotlinx.serialization.Serializable

@Serializable data class Nutrition(val calories: Decimal, val protein: Decimal, val fat: Decimal, val carbs: Decimal)
@Serializable data class NutritionInputSet(val calories: Decimal, val protein: Decimal, val fat: Decimal, val carbs: Decimal)
@Serializable data class Profile(val calorieTarget: Decimal?, val proteinTarget: Decimal?, val fatTarget: Decimal?, val carbsTarget: Decimal?)
@Serializable data class Account(val id: String, val email: String, val role: String, val profile: Profile)
@Serializable data class Credentials(val email: String, val password: String) {
  override fun toString() = "Credentials([redacted])"
}
@Serializable data class Refresh(val refreshToken: String) {
  override fun toString() = "Refresh([redacted])"
}
@Serializable data class Tokens(
    val accessToken: String, val refreshToken: String, val tokenType: String,
    val accessExpiresAt: String, val sessionExpiresAt: String, val account: Account,
) { override fun toString() = "Tokens([redacted])" }
@Serializable data class Component(val productId: String, val fraction: Decimal, val yieldFactor: Decimal?)
@Serializable data class Product(
    val id: String, val ownerId: String?, val name: String, val isDeleted: Boolean, val kind: String,
    val manualNutrition: NutritionInputSet?, val nutritionPer100g: Nutrition,
    val components: List<Component>, val manualYieldFactor: Decimal?, val effectiveYieldFactor: Decimal,
    val yieldSource: String, val productLabelIds: List<String>,
)
@Serializable data class ProductPage(val items: List<Product>, val nextCursor: String?)
@Serializable data class DiaryWrite(
    val productId: String, val quantity: Decimal, val unit: String, val occurredAt: String,
    val mealLabelIds: List<String>,
)
@Serializable data class DiaryEntry(
    val id: String, val productId: String, val productName: String, val productDeleted: Boolean,
    val consumedGrams: Decimal, val occurredAt: String, val mealLabelIds: List<String>, val nutrition: Nutrition,
)
@Serializable data class DiaryDay(val date: String, val zone: String, val entries: List<DiaryEntry>, val total: Nutrition)
@Serializable data class MealLabel(val id: String, val name: String)
@Serializable data class FieldError(val field: String, val code: String)
@Serializable data class Problem(
    val type: String, val title: String, val status: Int, val detail: String, val code: String,
    val correlationId: String, val errors: List<FieldError> = emptyList(),
)
@Serializable data class Readiness(val status: String)
