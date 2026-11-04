package ru.calorica.catalog

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.calorica.catalog.api.CompositionInput
import ru.calorica.catalog.domain.CatalogFailure
import java.util.UUID

class CompositionValidationTest {
    private val mapper = jacksonObjectMapper()
    private val id = UUID.randomUUID()
    private fun parse(json: String) = CompositionInput.parse(mapper.readTree(json))
    @Test fun `PERCENT is exact and hidden factor presence remains distinct from null`() {
        val absent = parse("""{"inputMode":"PERCENT","components":[{"productId":"$id","percent":"100"}]}""")
        assertThat(absent.components.single().fraction).isEqualByComparingTo("1")
        assertThat(absent.components.single().yieldFactor.present).isFalse()
        val cleared = parse("""{"inputMode":"PERCENT","components":[{"productId":"$id","percent":"100","yieldFactor":null}],"manualYieldFactor":null}""")
        assertThat(cleared.components.single().yieldFactor.present).isTrue()
        assertThat(cleared.components.single().yieldFactor.value).isNull()
        assertThat(cleared.manualYieldFactor.present).isTrue()
    }
    @Test fun `invalid percentages duplicated nodes mixed modes and excessive scale are rejected`() {
        for (percent in listOf("0", "-1", "99", "100.00000000001", "1e2", "+100", "NaN"))
            assertThatThrownBy { parse("""{"inputMode":"PERCENT","components":[{"productId":"$id","percent":"$percent"}]}""") }
                .isInstanceOf(CatalogFailure::class.java)
        for (json in listOf(
            """{"inputMode":"PERCENT","components":[{"productId":"$id","percent":"50"},{"productId":"$id","percent":"50"}]}""",
            """{"inputMode":"GRAMS","components":[{"productId":"$id","grams":"100"}]}""",
            """{"inputMode":"PERCENT","components":[{"productId":"$id","percent":100}]}""",
            """{"inputMode":"PERCENT","components":null}""",
            """{"inputMode":"PERCENT","components":[],"outputGrams":"100"}"""))
            assertThatThrownBy { parse(json) }.isInstanceOf(CatalogFailure::class.java)
    }
    @Test fun `three exact decimal shares round trip and empty composition accepts manual nutrition`() {
        val ids = (1..3).map { UUID.randomUUID() }
        val portions = listOf("33.3333333334", "33.3333333333", "33.3333333333")
        val body = ids.zip(portions).joinToString(",") { (id, p) -> """{"productId":"$id","percent":"$p"}""" }
        val values = parse("""{"inputMode":"PERCENT","components":[$body]}""")
        assertThat(values.components.map { it.fraction.toPlainString() }).containsExactly("0.333333333334", "0.333333333333", "0.333333333333")
        val leaf = parse("""{"inputMode":"PERCENT","components":[],"manualNutrition":{"calories":"100","protein":"1","fat":"2","carbs":"3"}}""")
        assertThat(leaf.manualNutrition!!.calories).isEqualByComparingTo("100")
    }
}
