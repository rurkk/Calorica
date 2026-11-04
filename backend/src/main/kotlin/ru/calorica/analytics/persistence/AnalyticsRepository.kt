package ru.calorica.analytics.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.calorica.analytics.domain.AnalyticsDay
import ru.calorica.analytics.domain.CalendarWindow
import ru.calorica.analytics.domain.MealGroup
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.diary.application.DiaryAnalyticsEntry
import ru.calorica.diary.domain.DiaryValues
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CalendarTotals(val days: List<AnalyticsDay>, val total: Nutrition,
    val dailyAverage: Nutrition, val mealLabels: List<MealGroup>)

@Repository
class AnalyticsRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    /** Only aggregation lives here: the catalog and diary have already calculated each portion.
     * All sums and division stay in PostgreSQL NUMERIC. Label expansion cannot multiply the total. */
    fun summarize(entries: List<DiaryAnalyticsEntry>, window: CalendarWindow): CalendarTotals {
        val inputs = entries.map { entry -> mapOf(
            "day" to entry.occurredAt.atZone(window.zone).toLocalDate().toString(),
            "labels" to entry.mealLabelIds,
            "calories" to entry.nutrition.calories.toPlainString(),
            "protein" to entry.nutrition.protein.toPlainString(),
            "fat" to entry.nutrition.fat.toPlainString(),
            "carbs" to entry.nutrition.carbs.toPlainString()) }
        data class Row(val kind: String, val key: String?, val nutrition: Nutrition)
        val values = jdbc.sql("""
            WITH portions AS (
                SELECT * FROM jsonb_to_recordset(CAST(:inputs AS jsonb))
                AS p(day date,labels uuid[],calories numeric,protein numeric,fat numeric,carbs numeric)
            ), total AS (
                SELECT coalesce(sum(calories),0) AS calories, coalesce(sum(protein),0) AS protein,
                    coalesce(sum(fat),0) AS fat, coalesce(sum(carbs),0) AS carbs FROM portions
            )
            SELECT 'total' AS kind, NULL::text AS key, calories,protein,fat,carbs FROM total
            UNION ALL
            SELECT 'average', NULL, calories/:days,protein/:days,fat/:days,carbs/:days FROM total
            UNION ALL
            SELECT 'day',day::text,sum(calories),sum(protein),sum(fat),sum(carbs) FROM portions GROUP BY day
            UNION ALL
            SELECT 'meal',label::text,sum(calories),sum(protein),sum(fat),sum(carbs)
            FROM portions CROSS JOIN LATERAL unnest(labels) label GROUP BY label
        """).param("inputs", mapper.writeValueAsString(inputs)).param("days", window.dayCount).query { rs, _ ->
            fun number(name: String): BigDecimal = rs.getBigDecimal(name).also {
                if (it.precision() - it.scale() > 100 || it.scale() > 1000)
                    DiaryValues.invalid("nutrition", "CALCULATION_LIMIT")
            }
            Row(rs.getString("kind"), rs.getString("key"),
                Nutrition(number("calories"), number("protein"), number("fat"), number("carbs")))
        }.list()
        val byDay = values.filter { it.kind == "day" }.associate { LocalDate.parse(it.key) to it.nutrition }
        val zero = Nutrition(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        return CalendarTotals(window.dates.map { AnalyticsDay(it, byDay[it] ?: zero) },
            values.single { it.kind == "total" }.nutrition, values.single { it.kind == "average" }.nutrition,
            values.filter { it.kind == "meal" }.sortedBy { it.key }.map { MealGroup(UUID.fromString(it.key), it.nutrition) })
    }
}
