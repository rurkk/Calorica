package ru.calorica.catalog.application.labels

import org.springframework.stereotype.Service
import ru.calorica.catalog.application.*
import ru.calorica.catalog.domain.*
import ru.calorica.catalog.domain.labels.ProductLabel
import ru.calorica.catalog.domain.labels.ProductLabelGrams
import ru.calorica.catalog.domain.labels.ProductLabelValues
import ru.calorica.catalog.persistence.labels.ProductLabelsRepository
import ru.calorica.platform.security.Actor
import java.util.UUID

@Service
class ProductLabels(private val repository: ProductLabelsRepository, private val catalog: CatalogService,
    private val transactions: CatalogTransactions, private val locks: CatalogMutationLock) {
    fun list(): List<ProductLabel> = transactions.snapshot { repository.list() }
    fun create(actor: Actor, name: String): ProductLabel = transactions.mutation { tx ->
        locks.base(actor, tx)
        val label = ProductLabel(UUID.randomUUID(), ProductNames.name(name))
        repository.insert(label.id, label.name)
        label
    }
    fun rename(actor: Actor, id: UUID, name: String): ProductLabel = transactions.mutation { tx ->
        locks.base(actor, tx)
        val label = ProductLabel(id, ProductNames.name(name))
        if (repository.rename(id, label.name) != 1) CatalogFailure.notFound()
        label
    }
    fun delete(actor: Actor, id: UUID) { transactions.mutation { tx ->
        locks.base(actor, tx)
        if (repository.delete(id) != 1) CatalogFailure.notFound()
        Unit
    } }
    fun assign(actor: Actor, productId: UUID, ids: List<UUID>, base: Boolean = false): Product = transactions.mutation { tx ->
        if (base) locks.base(actor, tx) else locks.personal(actor, tx)
        catalog.getForLabelAssignment(actor, productId, tx, base)
        ProductLabelValues.assignment(ids)
        if (repository.existing(ids) != ids.size) CatalogFailure.notFound()
        repository.replace(productId, ids)
        catalog.getForLabelAssignment(actor, productId, tx, base)
    }
}

@Service
class ProductLabelDistribution(private val calculation: CatalogCalculation, private val repository: ProductLabelsRepository,
    private val transactions: CatalogTransactions) : ProductLabelRead {
    override fun distribution(actor: Actor, portions: List<CatalogPortion>, snapshot: CatalogSnapshot): List<ProductLabelGrams> {
        transactions.requireSnapshot(snapshot)
        return repository.distribution(calculation.allocateLeaves(actor, portions, snapshot))
    }
}
