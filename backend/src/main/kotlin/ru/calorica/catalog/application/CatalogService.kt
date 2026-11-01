package ru.calorica.catalog.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import ru.calorica.platform.security.Actor
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.catalog.domain.Product
import ru.calorica.catalog.domain.ProductNames
import ru.calorica.catalog.domain.ProductPage
import ru.calorica.catalog.domain.CatalogPortion
import ru.calorica.catalog.persistence.CatalogRepository
import ru.calorica.catalog.domain.LeafAllocation
import ru.calorica.catalog.persistence.SearchKey
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

interface CatalogRead {
    fun getVisible(actor: Actor, ids: Collection<UUID>, includeDeletedForHistory: Boolean = false): List<Product>
    fun requireSelectable(actor: Actor, id: UUID, tx: CatalogWriteTransaction): Product
}
data class CalculatedNutrition(val nutritionPer100g: Nutrition, val effectiveYieldFactor: BigDecimal,
    val yieldSource: String)
interface CatalogCalculation {
    fun nutrition(actor: Actor, productIds: Collection<UUID>, snapshot: CatalogSnapshot): Map<UUID, CalculatedNutrition>
    fun allocateLeaves(actor: Actor, portions: List<CatalogPortion>, snapshot: CatalogSnapshot): List<LeafAllocation>
}

@Service
class CatalogService(
    private val repository: CatalogRepository,
    private val transactions: CatalogTransactions,
    private val locks: CatalogMutationLock,
    private val mapper: ObjectMapper,
) : CatalogRead, CatalogCalculation {
    private fun rows(actor: Actor, ids: Collection<UUID>, history: Boolean): List<Product> {
        if (ids.size > 10000) CatalogFailure.invalid("productIds", "CALCULATION_LIMIT")
        val unique = ids.toSet()
        val products = repository.visible(actor.id, unique, history)
        if (products.size != unique.size) CatalogFailure.notFound()
        val byId = products.associateBy { it.id }
        return unique.map { byId.getValue(it) }
    }
    override fun getVisible(actor: Actor, ids: Collection<UUID>, includeDeletedForHistory: Boolean): List<Product> =
        transactions.snapshot { rows(actor, ids, includeDeletedForHistory) }
    override fun requireSelectable(actor: Actor, id: UUID, tx: CatalogWriteTransaction): Product {
        locks.personal(actor, tx)
        return rows(actor, listOf(id), false).single()
    }
    override fun nutrition(actor: Actor, productIds: Collection<UUID>, snapshot: CatalogSnapshot): Map<UUID, CalculatedNutrition> {
        transactions.requireSnapshot(snapshot)
        return rows(actor, productIds, true).associate { it.id to CalculatedNutrition(it.nutritionPer100g, BigDecimal.ONE, "DEFAULT") }
    }
    override fun allocateLeaves(actor: Actor, portions: List<CatalogPortion>, snapshot: CatalogSnapshot): List<LeafAllocation> {
        transactions.requireSnapshot(snapshot)
        if (portions.size > 10000) CatalogFailure.invalid("portions", "CALCULATION_LIMIT")
        if (portions.map { it.rootOrdinal }.toSet().size != portions.size) CatalogFailure.invalid("rootOrdinal")
        portions.forEach {
            if (it.consumedGrams.signum() <= 0 || it.consumedGrams.scale() > 3 ||
                it.consumedGrams > BigDecimal("9999999.999")) CatalogFailure.invalid("consumedGrams")
        }
        rows(actor, portions.map { it.productId }, true)
        return repository.allocate(actor.id, portions)
    }
    fun get(actor: Actor, id: UUID): Product = getVisible(actor, listOf(id), true).single()
    private fun fingerprint(actor: Actor, q: String): String = MessageDigest.getInstance("SHA-256")
        .digest("${actor.id}\n$q".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun decode(cursor: String?, fingerprint: String): SearchKey? {
        if (cursor == null) return null
        if (cursor.isEmpty() || cursor.length > 2048) CatalogFailure.badRequest()
        try {
            val node = mapper.readTree(Base64.getUrlDecoder().decode(cursor))
            if (!node.isObject || node.size() != 6 || node["fingerprint"]?.asText() != fingerprint ||
                !node["tier"].isInt || node["tier"].intValue() !in 0..2 || !node["score"].isTextual ||
                !node["name"].isTextual || !node["id"].isTextual || node["version"]?.intValue() != 1)
                CatalogFailure.badRequest()
            val scoreText = node["score"].textValue()
            if (scoreText.length > 32 || !Regex("[01](\\.[0-9]+)?").matches(scoreText)) CatalogFailure.badRequest()
            val score = scoreText.toBigDecimal()
            if (score > BigDecimal.ONE || node["name"].textValue().length > 400) CatalogFailure.badRequest()
            return SearchKey(node["tier"].intValue(), score, node["name"].textValue(), UUID.fromString(node["id"].textValue()))
        } catch (e: CatalogFailure) { throw e } catch (e: Exception) { CatalogFailure.badRequest() }
    }
    private fun encode(key: SearchKey, fingerprint: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(mapper.writeValueAsBytes(mapOf("version" to 1, "fingerprint" to fingerprint,
            "tier" to key.tier, "score" to key.score.toPlainString(), "name" to key.name, "id" to key.id.toString())))
    fun search(actor: Actor, query: String?, limit: Int, cursor: String?): ProductPage = transactions.snapshot {
        if (limit !in 1..100 || (query?.codePointCount(0, query.length) ?: 0) > 200) CatalogFailure.badRequest()
        val q = repository.normalize(query ?: "")
        val fingerprint = fingerprint(actor, q)
        val hits = repository.search(actor.id, q, limit + 1, decode(cursor, fingerprint))
        val page = hits.take(limit)
        ProductPage(page.map { it.product }, if (hits.size > limit) encode(page.last().key, fingerprint) else null)
    }
    private fun validateManual(nutrition: Nutrition) {
        listOf("calories" to nutrition.calories, "protein" to nutrition.protein,
            "fat" to nutrition.fat, "carbs" to nutrition.carbs).forEach { (field, value) ->
            if (value.scale() > 2) CatalogFailure.invalid("manualNutrition.$field", "PRECISION_EXCEEDED")
            if (value.signum() < 0 || value > BigDecimal("99999.99")) CatalogFailure.invalid("manualNutrition.$field")
        }
    }
    fun create(actor: Actor, name: String, nutrition: Nutrition, base: Boolean = false): Product = transactions.mutation { tx ->
        validateManual(nutrition)
        if (base) locks.base(actor, tx) else locks.personal(actor, tx)
        val id = UUID.randomUUID()
        repository.insert(id, if (base) null else actor.id, ProductNames.name(name), nutrition)
        rows(actor, listOf(id), false).single()
    }
    fun patch(actor: Actor, id: UUID, name: String?, nutrition: Nutrition?, base: Boolean = false): Product = transactions.mutation { tx ->
        if (base) locks.base(actor, tx) else locks.personal(actor, tx)
        nutrition?.let(::validateManual)
        val current = rows(actor, listOf(id), false).single()
        if (if (base) current.ownerId != null else current.ownerId != actor.id) CatalogFailure.notFound()
        if (repository.update(id, if (base) null else actor.id, name?.let(ProductNames::name) ?: current.name,
                nutrition ?: current.manualNutrition) != 1) CatalogFailure.notFound()
        rows(actor, listOf(id), false).single()
    }
    fun delete(actor: Actor, id: UUID, mode: String, base: Boolean = false) { transactions.mutation { tx ->
        if (base) locks.base(actor, tx) else locks.personal(actor, tx)
        if (mode != "KEEP_REFERENCES") CatalogFailure.invalid("mode")
        val current = rows(actor, listOf(id), false).single()
        if (if (base) current.ownerId != null else current.ownerId != actor.id) CatalogFailure.notFound()
        if (repository.softDelete(id, if (base) null else actor.id) != 1) CatalogFailure.notFound()
        Unit
    } }
}
