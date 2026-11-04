package ru.calorica.catalog.application.labels

import ru.calorica.catalog.application.CatalogSnapshot
import ru.calorica.catalog.domain.CatalogPortion
import ru.calorica.catalog.domain.labels.ProductLabelGrams
import ru.calorica.platform.security.Actor

/** T13 owns the implementation: allocate through CatalogCalculation, then group leaf labels
 * using NUMERIC within the caller's live read snapshot. Overlapping groups may repeat grams. */
interface ProductLabelRead {
    fun distribution(actor: Actor, portions: List<CatalogPortion>, snapshot: CatalogSnapshot): List<ProductLabelGrams>
}
