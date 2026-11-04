package ru.calorica.catalog.persistence.labels

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.LeafAllocation
import ru.calorica.catalog.domain.labels.ProductLabel
import ru.calorica.catalog.domain.labels.ProductLabelGrams
import java.util.UUID

/** No graph arithmetic here: label grouping receives only the authoritative leaf allocation. */
@Repository
class ProductLabelsRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    fun list(): List<ProductLabel> = jdbc.sql("SELECT id,name FROM calorica.product_labels ORDER BY id")
        .query { rs, _ -> ProductLabel(rs.getObject("id", UUID::class.java), rs.getString("name")) }.list()

    fun insert(id: UUID, name: String) {
        jdbc.sql("INSERT INTO calorica.product_labels(id,name) VALUES (:id,:name)")
            .param("id", id).param("name", name).update()
    }
    fun rename(id: UUID, name: String): Int = jdbc.sql("UPDATE calorica.product_labels SET name=:name WHERE id=:id")
        .param("id", id).param("name", name).update()
    fun delete(id: UUID): Int = jdbc.sql("DELETE FROM calorica.product_labels WHERE id=:id").param("id", id).update()
    fun existing(ids: List<UUID>): Int = if (ids.isEmpty()) 0 else jdbc.sql(
        "SELECT count(*) FROM calorica.product_labels WHERE id IN (:ids)").param("ids", ids).query(Int::class.java).single()

    fun forProducts(ids: Collection<UUID>): Map<UUID, List<UUID>> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql("""SELECT product_id,label_id FROM calorica.product_label_links
            WHERE product_id IN (:ids) ORDER BY product_id,label_id""").param("ids", ids)
            .query { rs, _ -> rs.getObject("product_id", UUID::class.java) to rs.getObject("label_id", UUID::class.java) }
            .list().groupBy({ it.first }, { it.second })
    }
    fun replace(productId: UUID, ids: List<UUID>) {
        jdbc.sql("DELETE FROM calorica.product_label_links WHERE product_id=:id").param("id", productId).update()
        ids.forEach { id -> jdbc.sql("INSERT INTO calorica.product_label_links(product_id,label_id) VALUES (:product,:label)")
            .param("product", productId).param("label", id).update() }
    }

    fun distribution(leaves: List<LeafAllocation>): List<ProductLabelGrams> {
        if (leaves.isEmpty()) return emptyList()
        // One JSON parameter avoids PostgreSQL's bind-parameter limit for large, valid allocations.
        // Decimal strings are cast to unconstrained NUMERIC; no double or intermediate rounding.
        val input = mapper.writeValueAsString(leaves.map { mapOf("leaf_id" to it.leafId.toString(),
            "grams" to it.allocatedGrams.toPlainString()) })
        return jdbc.sql("""SELECT links.label_id,sum(leaf.grams) AS grams
            FROM jsonb_to_recordset(CAST(:leaves AS jsonb)) AS leaf(leaf_id uuid,grams numeric)
            JOIN calorica.product_label_links links ON links.product_id=leaf.leaf_id
            GROUP BY links.label_id ORDER BY links.label_id""").param("leaves", input)
            .query { rs, _ ->
                val grams = rs.getBigDecimal("grams")
                if (grams.scale() > 1000 || grams.precision() - grams.scale() > 100)
                    CatalogFailure.invalid("calculation", "CALCULATION_LIMIT")
                ProductLabelGrams(rs.getObject("label_id", UUID::class.java), grams)
            }.list()
    }
}
