package ru.calorica.analytics.domain

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.catalog.domain.PlainDecimalSerializer
import ru.calorica.diary.domain.DiaryValues
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class AnalyticsDay(val date: LocalDate, val total: Nutrition)
data class MealGroup(val labelId: UUID, val total: Nutrition)
data class ProductGroup(val labelId: UUID,
    @get:JsonSerialize(using = PlainDecimalSerializer::class) val grams: BigDecimal)
data class Analytics(val startDate: LocalDate, val endDateExclusive: LocalDate, val zone: String,
    val dayCount: Int, val days: List<AnalyticsDay>, val total: Nutrition, val dailyAverage: Nutrition,
    val mealLabels: List<MealGroup>, val productLabels: List<ProductGroup>)

/** Calendar boundaries are resolved independently; a calendar day need not last 24 hours. */
data class CalendarWindow(val startDate: LocalDate, val endDateExclusive: LocalDate, val zone: ZoneId) {
    val dayCount: Int = ChronoUnit.DAYS.between(startDate, endDateExclusive).toInt()
    val dates: List<LocalDate> = List(dayCount) { startDate.plusDays(it.toLong()) }
    val start: Instant = startDate.atStartOfDay(zone).toInstant()
    val end: Instant = endDateExclusive.atStartOfDay(zone).toInstant()

    companion object {
        fun select(date: String, zone: String, period: String): CalendarWindow {
            val selected = DiaryValues.day(date)
            val timezone = DiaryValues.zone(zone)
            val first = when (period) {
                "DAY" -> selected
                "WEEK" -> selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                "MONTH" -> selected.withDayOfMonth(1)
                else -> DiaryValues.invalid("period")
            }
            val end = when (period) {
                "DAY" -> first.plusDays(1)
                "WEEK" -> first.plusWeeks(1)
                else -> first.plusMonths(1)
            }
            // Keep both response dates inside the four-digit API calendar.
            if (first.year !in 1..9999 || end.year !in 1..9999) DiaryValues.invalid("date")
            return CalendarWindow(first, end, timezone)
        }
    }
}
