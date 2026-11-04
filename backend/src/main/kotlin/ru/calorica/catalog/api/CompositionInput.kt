package ru.calorica.catalog.api

import com.fasterxml.jackson.databind.JsonNode
import ru.calorica.catalog.domain.*
import java.math.BigDecimal

object CompositionInput {
    private fun fields(node: JsonNode, allowed: Set<String>) {
        if (!node.isObject || node.fieldNames().asSequence().any { it !in allowed }) CatalogFailure.badRequest()
    }
    private fun text(node: JsonNode?, field: String): String {
        if (node == null || node.isNull) CatalogFailure.invalid(field, "INVALID_COMPOSITION")
        if (!node.isTextual) CatalogFailure.badRequest()
        return node.textValue()
    }
    private fun decimal(node: JsonNode?, field: String, maximum: BigDecimal, scale: Int): BigDecimal {
        val value = text(node, field)
        if (value.length > 32 || !Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?").matches(value)) CatalogFailure.invalid(field)
        val number = value.toBigDecimal()
        if (number.scale() > scale) CatalogFailure.invalid(field, "PRECISION_EXCEEDED")
        if (number.signum() <= 0 || number > maximum) CatalogFailure.invalid(field)
        return number
    }
    private fun factor(node: JsonNode, field: String): OptionalFactor = when {
        !node.has(field) -> OptionalFactor()
        node[field].isNull -> OptionalFactor(true, null)
        else -> OptionalFactor(true, decimal(node[field], field, BigDecimal("9999.999999999999"), 12))
    }
    fun parse(node: JsonNode): CompositionWrite {
        // GRAMS/outputGrams are deliberately left to T11; reject without a partial write.
        fields(node, setOf("inputMode", "components", "manualYieldFactor", "manualNutrition"))
        if (text(node["inputMode"], "inputMode") != "PERCENT") CatalogFailure.invalid("inputMode", "INVALID_COMPOSITION")
        val children = node["components"] ?: CatalogFailure.invalid("components", "INVALID_COMPOSITION")
        if (!children.isArray) { if (children.isNull) CatalogFailure.invalid("components"); CatalogFailure.badRequest() }
        if (children.size() > 100) CatalogFailure.invalid("components", "CALCULATION_LIMIT")
        val result = children.mapIndexed { index, child ->
            fields(child, setOf("productId", "percent", "yieldFactor"))
            ComponentWrite(CatalogIds.parse(text(child["productId"], "components[$index].productId")),
                decimal(child["percent"], "components[$index].percent", BigDecimal("100"), 10).movePointLeft(2),
                factor(child, "yieldFactor"))
        }
        if (result.map { it.productId }.toSet().size != result.size) CatalogFailure.invalid("components", "INVALID_COMPOSITION")
        if (result.isNotEmpty() && result.fold(BigDecimal.ZERO) { sum, c -> sum + c.fraction }.compareTo(BigDecimal.ONE) != 0)
            CatalogFailure.invalid("components", "INVALID_COMPOSITION")
        val nutrition = node["manualNutrition"]?.let { CatalogInput.patch(
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().set<JsonNode>("manualNutrition", it)).second }
        return CompositionWrite(result, factor(node, "manualYieldFactor"), nutrition)
    }
}
