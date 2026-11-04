package ru.calorica.diary.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.calorica.catalog.application.CalculatedNutrition
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.diary.domain.DiaryValues
import ru.calorica.diary.domain.MealLabel
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class DiaryRow(val id: UUID, val productId: UUID, val consumedGrams: BigDecimal,
    val occurredAt: Instant, val mealLabelIds: List<UUID>)
data class DiaryNutrition(val entries: Map<Int, Nutrition>, val total: Nutrition)

@Repository
class DiaryRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    private val columns = """e.id, e.product_id, e.consumed_grams, e.occurred_at,
        ARRAY(SELECT l.label_id FROM calorica.diary_entry_labels l WHERE l.entry_id=e.id ORDER BY l.label_id) AS labels"""
    private fun row(rs: ResultSet): DiaryRow {
        val labels = rs.getArray("labels")
        try {
            return DiaryRow(rs.getObject("id", UUID::class.java), rs.getObject("product_id", UUID::class.java),
                rs.getBigDecimal("consumed_grams"), rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                (labels.array as Array<*>).map { it as UUID })
        } finally { labels.free() }
    }
    fun labels(): List<MealLabel> = jdbc.sql("SELECT id,name FROM calorica.meal_labels ORDER BY position,id")
        .query { rs, _ -> MealLabel(rs.getObject("id", UUID::class.java), rs.getString("name")) }.list()
    fun requireLabels(ids: List<UUID>) {
        if (ids.isEmpty()) return
        if (jdbc.sql("SELECT count(*) FROM calorica.meal_labels WHERE id IN (:ids)").param("ids", ids)
                .query(Int::class.java).single() != ids.size) DiaryValues.invalid("mealLabelIds")
    }
    fun get(owner: UUID, id: UUID): DiaryRow? = jdbc.sql("SELECT $columns FROM calorica.diary_entries e WHERE e.owner_id=:owner AND e.id=:id")
        .param("owner", owner).param("id", id).query { rs, _ -> row(rs) }.optional().orElse(null)
    fun day(owner: UUID, start: Instant, end: Instant): List<DiaryRow> {
        val rows = jdbc.sql("""SELECT $columns FROM calorica.diary_entries e WHERE e.owner_id=:owner
            AND e.occurred_at>=:start AND e.occurred_at<:end ORDER BY e.occurred_at,e.id LIMIT 10001""")
            .param("owner", owner).param("start", start.atOffset(ZoneOffset.UTC)).param("end", end.atOffset(ZoneOffset.UTC))
            .query { rs, _ -> row(rs) }.list()
        // A sentinel is only a guard; never return a truncated day or total.
        if (rows.size > 10000) DiaryValues.invalid("entries", "CALCULATION_LIMIT")
        return rows
    }
    fun insert(owner: UUID, row: DiaryRow) {
        jdbc.sql("""INSERT INTO calorica.diary_entries(id,owner_id,product_id,consumed_grams,occurred_at)
            VALUES(:id,:owner,:product,:grams,:at)""")
            .param("id", row.id).param("owner", owner).param("product", row.productId)
            .param("grams", row.consumedGrams).param("at", row.occurredAt.atOffset(ZoneOffset.UTC)).update()
        replaceLabels(row.id, row.mealLabelIds)
    }
    fun update(owner: UUID, row: DiaryRow) {
        if (jdbc.sql("""UPDATE calorica.diary_entries SET product_id=:product,consumed_grams=:grams,occurred_at=:at
            WHERE id=:id AND owner_id=:owner""")
            .param("id", row.id).param("owner", owner).param("product", row.productId)
            .param("grams", row.consumedGrams).param("at", row.occurredAt.atOffset(ZoneOffset.UTC)).update() != 1) DiaryValues.notFound()
        replaceLabels(row.id, row.mealLabelIds)
    }
    private fun replaceLabels(entry: UUID, labels: List<UUID>) {
        jdbc.sql("DELETE FROM calorica.diary_entry_labels WHERE entry_id=:id").param("id", entry).update()
        labels.forEach { label -> jdbc.sql("INSERT INTO calorica.diary_entry_labels(entry_id,label_id) VALUES(:id,:label)")
            .param("id", entry).param("label", label).update() }
    }
    fun delete(owner: UUID, id: UUID) {
        if (jdbc.sql("DELETE FROM calorica.diary_entries WHERE id=:id AND owner_id=:owner")
            .param("id", id).param("owner", owner).update() != 1) DiaryValues.notFound()
    }

    /** Scale catalog-calculated values by portions and sum before display rounding, entirely in PostgreSQL NUMERIC.
     * The catalog owns graph expansion. No catalog table is queried by diary persistence.
     * A JSON parameter avoids the PostgreSQL bind-parameter limit on a legal 10000-entry day. */
    fun nutrition(rows: List<DiaryRow>, calculated: Map<UUID, CalculatedNutrition>): DiaryNutrition {
        if (rows.isEmpty()) return DiaryNutrition(emptyMap(), Nutrition(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
        val inputs = rows.mapIndexed { index, row ->
            val n = calculated.getValue(row.productId).nutritionPer100g
            mapOf("ordinal" to index, "grams" to row.consumedGrams.toPlainString(), "calories" to n.calories.toPlainString(),
                "protein" to n.protein.toPlainString(), "fat" to n.fat.toPlainString(), "carbs" to n.carbs.toPlainString())
        }
        val result = jdbc.sql("""
            WITH portions AS (
                SELECT ordinal, grams*calories/100 AS calories, grams*protein/100 AS protein,
                    grams*fat/100 AS fat, grams*carbs/100 AS carbs
                FROM jsonb_to_recordset(CAST(:inputs AS jsonb)) AS p(ordinal integer,grams numeric,
                    calories numeric,protein numeric,fat numeric,carbs numeric)
            )
            SELECT ordinal,calories,protein,fat,carbs FROM portions
            UNION ALL SELECT -1,sum(calories),sum(protein),sum(fat),sum(carbs) FROM portions
        """).param("inputs", mapper.writeValueAsString(inputs)).query { rs, _ ->
            fun number(name: String): BigDecimal = rs.getBigDecimal(name).also {
                if (it.precision() - it.scale() > 100 || it.scale() > 1000) DiaryValues.invalid("nutrition", "CALCULATION_LIMIT")
            }
            rs.getInt("ordinal") to Nutrition(number("calories"), number("protein"), number("fat"), number("carbs"))
        }.list().toMap()
        return DiaryNutrition(result.filterKeys { it >= 0 }, result.getValue(-1))
    }
}
