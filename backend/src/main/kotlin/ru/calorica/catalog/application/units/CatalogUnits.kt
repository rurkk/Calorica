package ru.calorica.catalog.application.units

import org.springframework.stereotype.Service
import ru.calorica.catalog.application.CatalogMutationLock
import ru.calorica.catalog.application.CatalogTransactions
import ru.calorica.catalog.application.CatalogWriteTransaction
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.units.ProductUnit
import ru.calorica.catalog.domain.units.UnitValues
import ru.calorica.catalog.persistence.units.UnitsRepository
import ru.calorica.platform.security.Actor
import java.math.BigDecimal
import java.util.UUID

/** Catalog-owned conversion; diary persists only returned grams in its existing write transaction. */
@Service
class CatalogUnits(private val repository: UnitsRepository, private val transactions: CatalogTransactions,
    private val locks: CatalogMutationLock) {
    fun list(actor: Actor, productId: UUID): List<ProductUnit> = transactions.snapshot {
        val product = repository.visible(actor.id, productId) ?: CatalogFailure.notFound()
        buildList {
            add(ProductUnit("g", "1"))
            if (product.ownerId == null) product.millilitreFactor?.let { add(ProductUnit("ml", it.toPlainString())) }
        }
    }

    fun convert(actor: Actor, productId: UUID, quantity: BigDecimal, unit: String,
        tx: CatalogWriteTransaction): BigDecimal {
        transactions.requireWrite(tx)
        locks.personal(actor, tx)
        val product = repository.visible(actor.id, productId) ?: CatalogFailure.notFound()
        val factor = when (unit) {
            "g" -> BigDecimal.ONE
            "ml" -> if (product.ownerId == null) product.millilitreFactor else null
            else -> null
        } ?: CatalogFailure.invalid("unit", "UNIT_UNAVAILABLE")
        return UnitValues.mass(quantity, factor)
    }

    fun setMillilitres(actor: Actor, productId: UUID, factor: BigDecimal): ProductUnit = transactions.mutation { tx ->
        locks.base(actor, tx)
        val product = repository.visible(actor.id, productId) ?: CatalogFailure.notFound()
        if (product.ownerId != null || product.deleted) CatalogFailure.notFound()
        val checked = UnitValues.factor(factor.toPlainString())
        repository.setMillilitres(productId, checked)
        ProductUnit("ml", checked.toPlainString())
    }

    fun deleteMillilitres(actor: Actor, productId: UUID) { transactions.mutation { tx ->
        locks.base(actor, tx)
        val product = repository.visible(actor.id, productId) ?: CatalogFailure.notFound()
        if (product.ownerId != null || product.deleted) CatalogFailure.notFound()
        repository.deleteMillilitres(productId)
        Unit
    } }
}
