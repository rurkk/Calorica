package ru.calorica.catalog

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.calorica.catalog.api.CompositionInput
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.RecipeMasses
import java.math.BigDecimal
import java.util.UUID

class CompositionValidationTest {
    private val mapper = jacksonObjectMapper()
    private val id = UUID.randomUUID()
    private fun parse(json: String) = CompositionInput.parse(mapper.readTree(json))
    @Test fun `non object composition roots remain bad requests`() {
        for (root in listOf("null", "[]", "1", "true", "\"GRAMS\""))
            assertThatThrownBy { parse(root) }.isInstanceOfSatisfying(CatalogFailure::class.java) {
                assertThat(it.status).isEqualTo(400); assertThat(it.code).isEqualTo("BAD_REQUEST")
            }
    }
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
            """{"inputMode":"GRAMS","components":[{"productId":"$id","grams":"100","percent":"100"}]}""",
            """{"inputMode":"PERCENT","components":[{"productId":"$id","percent":100}]}""",
            """{"inputMode":"PERCENT","components":null}""",
            """{"inputMode":"PERCENT","components":[],"outputGrams":"100"}"""))
            assertThatThrownBy { parse(json) }.isInstanceOf(CatalogFailure::class.java)
    }
    private val orderedIds = listOf("00000000-0000-0000-0000-000000000001",
        "7fffffff-ffff-ffff-ffff-ffffffffffff", "80000000-0000-0000-0000-000000000000").map(UUID::fromString)
    private fun grams(masses: List<Pair<UUID, String>>, extra: String = "") = parse(
        """{"inputMode":"GRAMS","components":[${masses.joinToString(",") { (id,mass) ->
            """{"productId":"$id","grams":"$mass"}""" }}]$extra}""")
    @Test fun `equal gram masses distribute one grid unit by unsigned UUID order independent of input order`() {
        val expected = mapOf(orderedIds[0] to "0.333333333334", orderedIds[1] to "0.333333333333", orderedIds[2] to "0.333333333333")
        for (order in listOf(orderedIds, orderedIds.reversed(), listOf(orderedIds[2], orderedIds[0], orderedIds[1]))) {
            val value = grams(order.map { it to "1.000" })
            assertThat(value.components.map { it.productId }).containsExactlyElementsOf(order)
            value.components.forEach { assertThat(it.fraction).isEqualByComparingTo(expected.getValue(it.productId)) }
            assertThat(value.components.sumOf { it.fraction }).isEqualByComparingTo("1")
            assertThat(value.manualYieldFactor.present).isFalse()
        }
    }
    @Test fun `largest remainder wins before UUID and normalization uses exact decimal masses`() {
        val value = grams(orderedIds.zip(listOf("0.001", "0.002", "0.004")))
        assertThat(value.components.map { it.fraction.toPlainString() })
            .containsExactly("0.142857142857", "0.285714285714", "0.571428571429")
        val boundary = grams((0..99).map { UUID(0, it.toLong()) to if (it == 0) "0.001" else "9999999.999" })
        assertThat(boundary.components.sumOf { it.fraction }).isEqualByComparingTo("1")
        assertThat(boundary.components.first().fraction).isEqualByComparingTo("0.000000000001")
        assertThatThrownBy { RecipeMasses.fractions(listOf(orderedIds[0] to BigDecimal("0.0000000000001"), orderedIds[1] to BigDecimal.ONE)) }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("PRECISION_EXCEEDED") }
    }
    @Test fun `output grams compute only a persistable manual factor with HALF_UP twelve digits`() {
        assertThat(grams(listOf(id to "3"), """, "outputGrams":"1"""").manualYieldFactor.value)
            .isEqualByComparingTo("0.333333333333")
        assertThat(grams(listOf(id to "4096"), """, "outputGrams":"0.001"""").manualYieldFactor.value)
            .isEqualByComparingTo("0.000000244141")
        assertThat(grams(listOf(id to "8"), """, "outputGrams":"10"""").manualYieldFactor.value)
            .isEqualByComparingTo("1.25")
        assertThat(grams(listOf(id to "1"), """, "manualYieldFactor":null""").manualYieldFactor.present).isTrue()
        assertThat(grams(listOf(id to "1"), """, "manualYieldFactor":"1"""").manualYieldFactor.value).isEqualByComparingTo("1")
    }
    @Test fun `invalid masses modes and output fields fail instead of silently rounding`() {
        for (mass in listOf("0", "-1", "0.0001", "9999999.9991", "10000000", "1e2", "NaN", "Infinity"))
            assertThatThrownBy { grams(listOf(id to mass)) }.isInstanceOf(CatalogFailure::class.java)
        for (extra in listOf(""", "outputGrams":null""", """, "outputGrams":"0"""", """, "outputGrams":"0.0001"""",
            """, "outputGrams":"10000000"""", """, "manualNutrition":{"calories":"1","protein":"0","fat":"0","carbs":"0"}"""))
            assertThatThrownBy { grams(listOf(id to "1"), extra) }.isInstanceOf(CatalogFailure::class.java)
        assertThatThrownBy { grams(listOf(id to "0.001"), """, "outputGrams":"10"""") }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("VALIDATION_FAILED") }
        assertThatThrownBy { grams(emptyList()) }.isInstanceOf(CatalogFailure::class.java)
        assertThatThrownBy { grams(listOf(id to "1", id to "1")) }.isInstanceOf(CatalogFailure::class.java)
        for (manual in listOf("null", "\"1\""))
            assertThatThrownBy { grams(listOf(id to "1"), """, "outputGrams":"1", "manualYieldFactor":$manual""") }
                .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("YIELD_MODE_CONFLICT") }
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
