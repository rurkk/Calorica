package ru.calorica.catalog.domain.units

import ru.calorica.catalog.domain.CatalogFailure
import java.math.BigDecimal
import java.math.RoundingMode

data class ProductUnit(val code: String, val gramsPerUnit: String)

object UnitValues {
    private val syntax = Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?")
    private val massMaximum = BigDecimal("9999999.999")
    private val factorMaximum = BigDecimal("9999.999999999999")

    fun factor(value: String): BigDecimal {
        if (value.length > 32 || !syntax.matches(value)) CatalogFailure.invalid("gramsPerUnit")
        val factor = value.toBigDecimal()
        if (factor.scale() > 12) CatalogFailure.invalid("gramsPerUnit", "PRECISION_EXCEEDED")
        if (factor.signum() <= 0 || factor > factorMaximum) CatalogFailure.invalid("gramsPerUnit")
        return factor
    }

    fun mass(quantity: BigDecimal, factor: BigDecimal): BigDecimal {
        if (quantity.scale() > 3) CatalogFailure.invalid("quantity", "PRECISION_EXCEEDED")
        if (quantity.signum() <= 0 || quantity > massMaximum) CatalogFailure.invalid("quantity")
        val grams = quantity.multiply(factor).setScale(3, RoundingMode.HALF_UP)
        if (grams.signum() <= 0 || grams > massMaximum) CatalogFailure.invalid("quantity")
        return grams
    }
}
