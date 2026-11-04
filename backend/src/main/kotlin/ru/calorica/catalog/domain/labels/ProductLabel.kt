package ru.calorica.catalog.domain.labels

import ru.calorica.catalog.domain.CatalogFailure
import java.util.UUID

data class ProductLabel(val id: UUID, val name: String)

object ProductLabelValues {
    fun assignment(ids: List<UUID>): List<UUID> {
        if (ids.size > 100) CatalogFailure.invalid("labelIds", "CALCULATION_LIMIT")
        if (ids.toSet().size != ids.size) CatalogFailure.invalid("labelIds")
        return ids
    }
}
