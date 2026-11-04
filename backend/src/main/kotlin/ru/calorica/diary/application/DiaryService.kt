package ru.calorica.diary.application

import org.springframework.stereotype.Service
import ru.calorica.catalog.application.CatalogCalculation
import ru.calorica.catalog.application.CatalogMutationLock
import ru.calorica.catalog.application.CatalogRead
import ru.calorica.catalog.application.CatalogSnapshot
import ru.calorica.catalog.application.CatalogTransactions
import ru.calorica.catalog.application.CatalogWriteTransaction
import ru.calorica.catalog.application.CalculatedNutrition
import ru.calorica.catalog.application.units.CatalogUnits
import ru.calorica.catalog.domain.Product
import ru.calorica.diary.domain.*
import ru.calorica.diary.persistence.DiaryRepository
import ru.calorica.diary.persistence.DiaryRow
import ru.calorica.platform.security.Actor
import java.util.UUID

@Service
class DiaryService(private val repository: DiaryRepository, private val catalog: CatalogRead,
    private val calculation: CatalogCalculation, private val transactions: CatalogTransactions,
    private val locks: CatalogMutationLock, private val units: CatalogUnits) {
    fun labels(): List<MealLabel> = transactions.snapshot { repository.labels() }
    private fun render(actor: Actor, rows: List<DiaryRow>, snapshot: CatalogSnapshot): Pair<List<DiaryEntry>, ru.calorica.catalog.domain.Nutrition> {
        val ids = rows.map { it.productId }.toSet()
        return render(rows, catalog.getVisible(actor, ids, true), calculation.nutrition(actor, ids, snapshot))
    }
    private fun writtenEntry(actor: Actor, id: UUID, tx: CatalogWriteTransaction): DiaryEntry {
        val row = repository.get(actor.id, id) ?: DiaryValues.notFound()
        return render(listOf(row), catalog.getVisible(actor, listOf(row.productId), true, tx),
            calculation.nutrition(actor, listOf(row.productId), tx)).first.single()
    }
    private fun render(rows: List<DiaryRow>, visible: List<Product>, calculated: Map<UUID, CalculatedNutrition>): Pair<List<DiaryEntry>, ru.calorica.catalog.domain.Nutrition> {
        val products = visible.associateBy { it.id }
        val nutrition = repository.nutrition(rows, calculated)
        return rows.mapIndexed { index, row ->
            val product = products.getValue(row.productId)
            DiaryEntry(row.id, row.productId, product.name, product.isDeleted, row.consumedGrams,
                row.occurredAt, row.mealLabelIds, nutrition.entries.getValue(index))
        } to nutrition.total
    }
    fun day(actor: Actor, date: String, zone: String): DiaryDay {
        val local = DiaryValues.day(date)
        val timezone = DiaryValues.zone(zone)
        // atStartOfDay follows the zone's transitions, including 23/25-hour and skipped days.
        val start = local.atStartOfDay(timezone).toInstant()
        val end = local.plusDays(1).atStartOfDay(timezone).toInstant()
        return transactions.snapshot { snapshot ->
            val result = render(actor, repository.day(actor.id, start, end), snapshot)
            DiaryDay(local, timezone.id, result.first, result.second)
        }
    }
    fun get(actor: Actor, id: UUID): DiaryEntry = transactions.snapshot { snapshot ->
        val row = repository.get(actor.id, id) ?: DiaryValues.notFound()
        render(actor, listOf(row), snapshot).first.single()
    }
    fun create(actor: Actor, input: DiaryWrite): DiaryEntry =
        transactions.mutation { tx ->
            locks.personal(actor, tx)
            catalog.requireSelectable(actor, input.productId, tx)
            val labels = DiaryValues.labels(input.mealLabelIds)
            repository.requireLabels(labels)
            val grams = units.convert(actor, input.productId, input.quantity, input.unit, tx)
            val row = DiaryRow(UUID.randomUUID(), input.productId, grams, input.occurredAt, labels)
            repository.insert(actor.id, row)
            // Materialize while the same transaction and catalog locks still protect the write.
            // Validation/calculation failure rolls back the entry and its label links together.
            writtenEntry(actor, row.id, tx)
        }
    fun patch(actor: Actor, id: UUID, input: DiaryPatch): DiaryEntry =
        transactions.mutation { tx ->
            locks.personal(actor, tx)
            val old = repository.get(actor.id, id) ?: DiaryValues.notFound()
            val productId = input.productId ?: old.productId
            if (productId != old.productId) catalog.requireSelectable(actor, productId, tx)
            if ((input.quantity == null) != (input.unit == null)) DiaryValues.invalid("quantity")
            val grams = input.quantity?.let { units.convert(actor, productId, it, checkNotNull(input.unit), tx) } ?: old.consumedGrams
            val labels = input.mealLabelIds?.let(DiaryValues::labels) ?: old.mealLabelIds
            repository.requireLabels(labels)
            val row = DiaryRow(id, productId, grams, input.occurredAt ?: old.occurredAt, labels)
            repository.update(actor.id, row)
            writtenEntry(actor, id, tx)
        }
    fun delete(actor: Actor, id: UUID) { transactions.mutation { tx ->
        locks.personal(actor, tx)
        repository.delete(actor.id, id)
        Unit
    } }
}
