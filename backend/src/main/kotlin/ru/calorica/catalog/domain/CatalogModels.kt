package ru.calorica.catalog.domain

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID

class PlainDecimalSerializer : JsonSerializer<BigDecimal>() {
    override fun serialize(value: BigDecimal, generator: JsonGenerator, provider: SerializerProvider) =
        generator.writeString(value.toPlainString())
}

data class Nutrition(
    @get:JsonSerialize(using = PlainDecimalSerializer::class) val calories: BigDecimal,
    @get:JsonSerialize(using = PlainDecimalSerializer::class) val protein: BigDecimal,
    @get:JsonSerialize(using = PlainDecimalSerializer::class) val fat: BigDecimal,
    @get:JsonSerialize(using = PlainDecimalSerializer::class) val carbs: BigDecimal,
)

data class Product(
    val id: UUID, val ownerId: UUID?, val name: String, val isDeleted: Boolean,
    val manualNutrition: Nutrition,
    val nutritionPer100g: Nutrition,
    val kind: String = "LEAF",
    val components: List<Any> = emptyList(),
    val manualYieldFactor: String? = null,
    val effectiveYieldFactor: String = "1",
    val yieldSource: String = "DEFAULT",
    val productLabelIds: List<UUID> = emptyList(),
)

data class ProductPage(val items: List<Product>, val nextCursor: String?)
data class CatalogFieldError(val field: String, val code: String)
class CatalogFailure(val status: Int, val code: String, val errors: List<CatalogFieldError> = emptyList()) :
    RuntimeException(code) {
    companion object {
        fun invalid(field: String, code: String = "VALIDATION_FAILED"): Nothing =
            throw CatalogFailure(422, code, listOf(CatalogFieldError(field, code)))
        fun badRequest(): Nothing = throw CatalogFailure(400, "BAD_REQUEST")
        fun notFound(): Nothing = throw CatalogFailure(404, "NOT_FOUND")
        fun forbidden(): Nothing = throw CatalogFailure(403, "FORBIDDEN")
    }
}

/** Reference normalization; persisted names and queries both use the same SQL function. */
object ProductNames {
    fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace("ъ", "").replace("ь", "").replace(Regex("(?U)\\s+"), " ").trim()
    fun name(value: String): String = value.trim().also {
        if (it.isEmpty() || it.codePointCount(0, it.length) > 200) CatalogFailure.invalid("name")
    }
}

object NutritionValues {
    private val syntax = Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?")
    private val maximum = BigDecimal("99999.99")
    fun parse(value: String, field: String): BigDecimal {
        if (value.length > 32 || !syntax.matches(value)) CatalogFailure.invalid(field)
        val decimal = value.toBigDecimal()
        if (decimal.scale() > 2) CatalogFailure.invalid(field, "PRECISION_EXCEEDED")
        if (decimal > maximum) CatalogFailure.invalid(field)
        return decimal
    }
}

data class LeafAllocation(val rootOrdinal: Int, val leafId: UUID, val allocatedGrams: BigDecimal)
data class CatalogPortion(val rootOrdinal: Int, val productId: UUID, val consumedGrams: BigDecimal)


object CatalogIds {
    private val canonical = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun parse(value: String): UUID {
        if (!canonical.matches(value)) CatalogFailure.badRequest()
        return UUID.fromString(value)
    }
}
