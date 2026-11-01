package com.rurkk.calorica

import com.rurkk.calorica.core.navigation.*
import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
  @Test fun pickerKeepsCallerAndRequestIdentityAndRejectsRouteInjection() {
    val diary = ProductSelectionRequest.create(ProductSelectionPurpose.DIARY)
    val composition = ProductSelectionRequest.create(ProductSelectionPurpose.COMPOSITION)
    assertNotEquals(diary.requestId, composition.requestId)
    assertTrue(diary.route.endsWith("/DIARY"))
    val result = ProductSelectionResult(diary, ProductSelection("00000000-0000-4000-8000-000000000001"))
    assertEquals(result, ProductSelectionResult.decode(result.encode()))
    assertThrows(IllegalArgumentException::class.java) { ProductSelectionRequest("../products", ProductSelectionPurpose.DIARY) }
    assertThrows(IllegalArgumentException::class.java) { ProductSelection("not-a-uuid") }
  }
}
