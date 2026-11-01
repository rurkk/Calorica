package ru.calorica.catalog

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import ru.calorica.catalog.api.CatalogInput
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.NutritionValues
import ru.calorica.catalog.domain.ProductNames

class CatalogValidationTest {
    private val mapper = jacksonObjectMapper()
    @Test fun `normalization folds Russian letters whitespace and case`() {
        assertThat(ProductNames.normalize(" \tСЪЁМКА  Соль\nМОЛОКО  ")).isEqualTo("семка сол молоко")
        assertThat(ProductNames.name("  Соль  ")).isEqualTo("Соль")
        assertThatThrownBy { ProductNames.name(" \t ") }.isInstanceOf(CatalogFailure::class.java)
        assertThatThrownBy { ProductNames.name("я".repeat(201)) }.isInstanceOf(CatalogFailure::class.java)
    }
    @Test fun `manual nutrition requires finite unsigned plain decimal in bounded scale and range`() {
        for (valid in listOf("0", "0.00", "12.34", "99999.99"))
            assertThat(NutritionValues.parse(valid, "calories")).isEqualByComparingTo(valid)
        for (invalid in listOf("-1", "NaN", "Infinity", "1e2", "+1", ".5", "01", "100000", "99999.999", "1.000", "9".repeat(1000))) {
            assertThatThrownBy { NutritionValues.parse(invalid, "calories") }.isInstanceOf(CatalogFailure::class.java)
        }
    }
    @Test fun `patch distinguishes absent from forbidden explicit null and does not coerce numbers`() {
        assertThat(CatalogInput.patch(mapper.readTree("{}"))).isEqualTo(null to null)
        for (json in listOf("""{"name":null}""", """{"manualNutrition":null}""", """{"ownerId":"x"}""",
            """{"manualNutrition":{"calories":12,"protein":"0","fat":"0","carbs":"0"}}"""))
            assertThatThrownBy { CatalogInput.patch(mapper.readTree(json)) }.isInstanceOf(CatalogFailure::class.java)
        val result = CatalogInput.patch(mapper.readTree("""{"name":"  Новый  "}"""))
        assertThat(result.first).isEqualTo("Новый")
        assertThat(result.second).isNull()
    }
    @Test fun `unsupported composition is rejected without inventing manual nutrition`() {
        assertThatThrownBy { CatalogInput.create(mapper.readTree("""{"name":"Состав","composition":{}}""")) }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("INVALID_COMPOSITION") }
    }
}
