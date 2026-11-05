package com.rurkk.calorica.core.ui

import java.math.BigDecimal
import java.math.RoundingMode

/** Display only: retain the original decimal values for requests, forms and server totals. */
object DisplayNumbers {
  fun nutrition(value: BigDecimal): String = decimal(value, 2)
  fun totalCalories(value: BigDecimal): String = decimal(value, 0)
  fun mass(value: BigDecimal): String = decimal(value, 3)

  private fun decimal(value: BigDecimal, scale: Int): String =
      value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
