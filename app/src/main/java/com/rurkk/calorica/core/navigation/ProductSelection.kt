package com.rurkk.calorica.core.navigation

import java.util.UUID

enum class ProductSelectionPurpose { DIARY, COMPOSITION }
data class ProductSelectionRequest(val requestId: String, val purpose: ProductSelectionPurpose) {
  init { require(UUID.fromString(requestId).toString() == requestId) }
  val route: String get() = "product-picker/$requestId/${purpose.name}"
  val resultKey: String get() = "productSelection:$requestId"
  companion object {
    fun create(purpose: ProductSelectionPurpose) = ProductSelectionRequest(UUID.randomUUID().toString(), purpose)
  }
}
data class ProductSelection(val productId: String) {
  init { require(UUID.fromString(productId).toString() == productId) }
}
object Routes {
  const val AUTH = "auth"
  const val PRODUCTS = "products"
  const val DIARY = "diary"
  const val PRODUCT_PICKER = "product-picker/{requestId}/{purpose}"
}

/** Delivered to the requesting screen, retained by its navigation entry until consumed. */
data class ProductSelectionResult(val request: ProductSelectionRequest, val selection: ProductSelection) {
  fun encode(): String = "${request.requestId}|${request.purpose.name}|${selection.productId}"
  companion object {
    const val STATE_KEY = "productSelectionResult"
    fun decode(value: String): ProductSelectionResult {
      val parts = value.split('|')
      require(parts.size == 3)
      return ProductSelectionResult(ProductSelectionRequest(parts[0], ProductSelectionPurpose.valueOf(parts[1])), ProductSelection(parts[2]))
    }
  }
}
class ProductPickerHost(
    val result: ProductSelectionResult?,
    val launch: (ProductSelectionRequest) -> Unit,
    val consume: () -> Unit,
)
