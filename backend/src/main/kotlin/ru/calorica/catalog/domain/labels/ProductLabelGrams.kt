package ru.calorica.catalog.domain.labels

import java.math.BigDecimal
import java.util.UUID

data class ProductLabelGrams(val labelId: UUID, val grams: BigDecimal)
