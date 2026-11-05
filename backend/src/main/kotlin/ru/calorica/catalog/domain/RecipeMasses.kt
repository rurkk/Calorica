package ru.calorica.catalog.domain

import java.math.BigDecimal
import java.util.UUID

/** Normalizes validated input, without calculating nutrition or ready-mass allocation. */
object RecipeMasses {
    private val grid = BigDecimal("1000000000000")

    fun fractions(masses: List<Pair<UUID, BigDecimal>>): List<BigDecimal> {
        val total = masses.fold(BigDecimal.ZERO) { sum, item -> sum + item.second }
        // Integer division and exact remainders avoid rounding before the largest-remainder sort.
        val divisions = masses.map { (_, mass) -> mass.multiply(grid).divideAndRemainder(total) }
        val remaining = grid.subtract(divisions.fold(BigDecimal.ZERO) { sum, pair -> sum + pair[0] }).intValueExact()
        val winners = masses.indices.sortedWith(
            compareByDescending<Int> { divisions[it][1] }
                // UUID.compareTo uses signed longs; the contract uses PostgreSQL/unsigned UUID order.
                .thenBy { masses[it].first.toString() }
        ).take(remaining).toSet()
        return divisions.mapIndexed { index, pair ->
            val fraction = pair[0].add(if (index in winners) BigDecimal.ONE else BigDecimal.ZERO).movePointLeft(12)
            if (fraction.signum() <= 0) CatalogFailure.invalid("components[$index].grams", "PRECISION_EXCEEDED")
            fraction
        }
    }
}
