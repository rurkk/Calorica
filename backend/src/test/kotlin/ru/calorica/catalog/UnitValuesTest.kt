package ru.calorica.catalog

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.units.UnitValues
import java.math.BigDecimal

class UnitValuesTest {
    @Test fun `only finite positive plain factors with exact precision are accepted`() {
        listOf("0", "-1", "+1", "1e2", "NaN", "Infinity", "01", "1,03", " 1", "10000")
            .forEach { value -> assertThatThrownBy { UnitValues.factor(value) }.isInstanceOf(CatalogFailure::class.java) }
        assertThatThrownBy { UnitValues.factor("1.0000000000001") }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("PRECISION_EXCEEDED") }
        assertThat(UnitValues.factor("0.000000000001")).isEqualByComparingTo("0.000000000001")
        assertThat(UnitValues.factor("9999.999999999999")).isEqualByComparingTo("9999.999999999999")
    }

    @Test fun `portion conversion rounds once with HALF_UP and validates final mass`() {
        assertThat(UnitValues.mass(BigDecimal("200"), BigDecimal("1.03"))).isEqualTo(BigDecimal("206.000"))
        assertThat(UnitValues.mass(BigDecimal("0.001"), BigDecimal("0.5"))).isEqualTo(BigDecimal("0.001"))
        listOf("0", "-1", "10000000", "1.0001").forEach { value ->
            assertThatThrownBy { UnitValues.mass(BigDecimal(value), BigDecimal.ONE) }.isInstanceOf(CatalogFailure::class.java)
        }
        assertThatThrownBy { UnitValues.mass(BigDecimal("0.001"), BigDecimal("0.49")) }.isInstanceOf(CatalogFailure::class.java)
        assertThat(UnitValues.mass(BigDecimal("9999999.999"), BigDecimal("1.000000000001")))
            .isEqualTo(BigDecimal("9999999.999"))
        assertThatThrownBy { UnitValues.mass(BigDecimal("9999999.999"), BigDecimal("1.01")) }
            .isInstanceOf(CatalogFailure::class.java)
    }
}
