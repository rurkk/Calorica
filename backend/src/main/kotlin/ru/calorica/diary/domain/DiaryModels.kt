package ru.calorica.diary.domain

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.catalog.domain.PlainDecimalSerializer
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.api.FieldFailure
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class MealLabel(val id: UUID, val name: String)
data class DiaryEntry(val id: UUID, val productId: UUID, val productName: String, val productDeleted: Boolean,
    @get:JsonSerialize(using = PlainDecimalSerializer::class) val consumedGrams: BigDecimal,
    val occurredAt: Instant, val mealLabelIds: List<UUID>, val nutrition: Nutrition)
data class DiaryDay(val date: LocalDate, val zone: String, val entries: List<DiaryEntry>, val total: Nutrition)
data class DiaryWrite(val productId: UUID, val quantity: BigDecimal, val unit: String,
    val occurredAt: Instant, val mealLabelIds: List<UUID>)
data class DiaryPatch(val productId: UUID? = null, val quantity: BigDecimal? = null, val unit: String? = null,
    val occurredAt: Instant? = null, val mealLabelIds: List<UUID>? = null)

object DiaryValues {
    fun invalid(field: String, code: String = "VALIDATION_FAILED"): Nothing =
        throw ApiFailure(422, code, listOf(FieldFailure(field, code)))
    fun notFound(): Nothing = throw ApiFailure(404, "NOT_FOUND")
    fun badRequest(): Nothing = throw ApiFailure(400, "BAD_REQUEST")
    fun quantity(value: String): BigDecimal {
        if (value.length > 32 || !Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?").matches(value)) invalid("quantity")
        val number = value.toBigDecimal()
        if (number.scale() > 3) invalid("quantity", "PRECISION_EXCEEDED")
        if (number.signum() <= 0 || number > BigDecimal("9999999.999")) invalid("quantity")
        return number
    }
    fun occurredAt(value: String): Instant {
        if (value.length > 40 || !Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z").matches(value)
            || value.startsWith("0000")) invalid("occurredAt")
        return try { Instant.parse(value) } catch (_: java.time.DateTimeException) { invalid("occurredAt") }
    }
    fun day(value: String): LocalDate {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value) || value.startsWith("0000")) invalid("date")
        return try { LocalDate.parse(value) } catch (_: java.time.DateTimeException) { invalid("date") }
    }
    fun zone(value: String): ZoneId {
        if (value.length > 100 || value !in ZoneId.getAvailableZoneIds()) invalid("zone")
        return ZoneId.of(value)
    }
    fun labels(value: List<UUID>): List<UUID> {
        if (value.size > 4 || value.toSet().size != value.size) invalid("mealLabelIds")
        return value
    }
}
