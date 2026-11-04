package ru.calorica.catalog.api

import com.fasterxml.jackson.databind.JsonNode
import ru.calorica.catalog.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

object CompositionInput {
    private data class ComponentAmount(val productId: UUID, val amount: BigDecimal, val yieldFactor: OptionalFactor)
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
    private fun factor(node: JsonNode, field: String, path: String = field): OptionalFactor = when {
        !node.has(field) -> OptionalFactor()
        node[field].isNull -> OptionalFactor(true, null)
        else -> OptionalFactor(true, decimal(node[field], path, BigDecimal("9999.999999999999"), 12))
    }
    fun parse(node: JsonNode): CompositionWrite {
        if (!node.isObject) CatalogFailure.badRequest()
        val mode = text(node["inputMode"], "inputMode")
        if (mode !in setOf("PERCENT", "GRAMS")) CatalogFailure.invalid("inputMode", "INVALID_COMPOSITION")
        val grams = mode == "GRAMS"
        fields(node, setOf("inputMode", "components", "manualYieldFactor", if (grams) "outputGrams" else "manualNutrition"))
        val children = node["components"] ?: CatalogFailure.invalid("components", "INVALID_COMPOSITION")
        if (!children.isArray) { if (children.isNull) CatalogFailure.invalid("components"); CatalogFailure.badRequest() }
        if (children.size() > 100) CatalogFailure.invalid("components", "CALCULATION_LIMIT")
        if (grams && children.isEmpty) CatalogFailure.invalid("components", "INVALID_COMPOSITION")
        val amountField = if (grams) "grams" else "percent"
        val amounts = children.mapIndexed { index, child ->
            fields(child, setOf("productId", amountField, "yieldFactor"))
            ComponentAmount(CatalogIds.parse(text(child["productId"], "components[$index].productId")),
                decimal(child[amountField], "components[$index].$amountField",
                    if (grams) BigDecimal("9999999.999") else BigDecimal("100"), if (grams) 3 else 10),
                factor(child, "yieldFactor", "components[$index].yieldFactor"))
        }
        if (amounts.map { it.productId }.toSet().size != amounts.size) CatalogFailure.invalid("components", "INVALID_COMPOSITION")
        val fractions = if (grams) RecipeMasses.fractions(amounts.map { it.productId to it.amount })
            else amounts.map { it.amount.movePointLeft(2) }
        val result = amounts.zip(fractions) { child, fraction -> ComponentWrite(child.productId, fraction, child.yieldFactor) }
        if (result.isNotEmpty() && result.fold(BigDecimal.ZERO) { sum, c -> sum + c.fraction }.compareTo(BigDecimal.ONE) != 0)
            CatalogFailure.invalid("components", "INVALID_COMPOSITION")
        val nutrition = node["manualNutrition"]?.let { CatalogInput.patch(
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().set<JsonNode>("manualNutrition", it)).second }
        val manual = if (node.has("outputGrams")) {
            if (node.has("manualYieldFactor")) throw CatalogFailure(409, "YIELD_MODE_CONFLICT")
            val output = decimal(node["outputGrams"], "outputGrams", BigDecimal("9999999.999"), 3)
            val ratio = output.divide(amounts.fold(BigDecimal.ZERO) { sum, child -> sum + child.amount }, 12, RoundingMode.HALF_UP)
            if (ratio.signum() <= 0) CatalogFailure.invalid("outputGrams", "PRECISION_EXCEEDED")
            if (ratio > BigDecimal("9999.999999999999")) CatalogFailure.invalid("outputGrams")
            OptionalFactor(true, ratio)
        } else factor(node, "manualYieldFactor")
        // Recipe masses are transient input. The application receives only persistable fractions
        // and the optional manual factor; it resolves inherited edge factors under mutation locks.
        return CompositionWrite(result, manual, nutrition)
    }
}
