package ru.calorica.diary.application

import org.springframework.stereotype.Service
import ru.calorica.catalog.application.CatalogCalculation
import ru.calorica.catalog.application.CatalogSnapshot
import ru.calorica.catalog.application.CatalogTransactions
import ru.calorica.catalog.domain.CatalogPortion
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.diary.persistence.DiaryRepository
import ru.calorica.platform.security.Actor
import java.time.Instant
import java.util.UUID

data class DiaryAnalyticsEntry(val portion: CatalogPortion, val occurredAt: Instant,
    val mealLabelIds: List<UUID>, val nutrition: Nutrition)

/** Read capability for analytics. Reuses the diary's SQL portion scaling and catalog graph calculation. */
@Service
class DiaryAnalyticsRead(private val repository: DiaryRepository, private val calculation: CatalogCalculation,
    private val transactions: CatalogTransactions) {
    fun entries(actor: Actor, start: Instant, end: Instant, snapshot: CatalogSnapshot): List<DiaryAnalyticsEntry> {
        transactions.requireSnapshot(snapshot)
        val rows = repository.period(actor.id, start, end)
        val nutrition = repository.nutrition(rows, calculation.nutrition(actor, rows.map { it.productId }.toSet(), snapshot))
        return rows.mapIndexed { ordinal, row ->
            DiaryAnalyticsEntry(CatalogPortion(ordinal, row.productId, row.consumedGrams), row.occurredAt,
                row.mealLabelIds, nutrition.entries.getValue(ordinal))
        }
    }
}
