package com.rurkk.calorica.features.products

import com.rurkk.calorica.core.api.Product
import com.rurkk.calorica.core.navigation.ProductSelection
import com.rurkk.calorica.core.navigation.ProductSelectionRequest

/** A route produces at most one callback; navigation carries only the product UUID. */
class ProductSelectionDelivery(val request: ProductSelectionRequest) {
  private var delivered = false
  fun select(product: Product): ProductSelection? {
    if (delivered || product.isDeleted) return null
    delivered = true
    return ProductSelection(product.id)
  }
  fun cancel(): Boolean {
    if (delivered) return false
    delivered = true
    return true
  }
}
