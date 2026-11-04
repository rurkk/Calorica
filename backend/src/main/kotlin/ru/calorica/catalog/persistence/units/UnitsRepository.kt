package ru.calorica.catalog.persistence.units

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.UUID

data class UnitProduct(val ownerId: UUID?, val deleted: Boolean, val millilitreFactor: BigDecimal?)

@Repository
class UnitsRepository(private val jdbc: JdbcClient) {
    fun visible(actorId: UUID, productId: UUID): UnitProduct? = jdbc.sql("""
        SELECT p.owner_id, p.is_deleted, u.grams_per_unit
        FROM calorica.products p
        LEFT JOIN calorica.product_units u ON u.product_id=p.id AND u.unit_code='ml'
        WHERE p.id=:id AND (p.owner_id IS NULL OR p.owner_id=:actor)
    """).param("id", productId).param("actor", actorId).query { rs, _ ->
        UnitProduct(rs.getObject("owner_id", UUID::class.java), rs.getBoolean("is_deleted"),
            rs.getBigDecimal("grams_per_unit"))
    }.optional().orElse(null)

    fun setMillilitres(productId: UUID, factor: BigDecimal) {
        jdbc.sql("""INSERT INTO calorica.product_units(product_id,unit_code,grams_per_unit)
            VALUES (:id,'ml',:factor) ON CONFLICT (product_id,unit_code)
            DO UPDATE SET grams_per_unit=EXCLUDED.grams_per_unit""")
            .param("id", productId).param("factor", factor).update()
    }

    fun deleteMillilitres(productId: UUID) {
        jdbc.sql("DELETE FROM calorica.product_units WHERE product_id=:id AND unit_code='ml'")
            .param("id", productId).update()
    }
}
