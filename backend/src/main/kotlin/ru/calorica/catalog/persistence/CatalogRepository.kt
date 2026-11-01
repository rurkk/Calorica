package ru.calorica.catalog.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.calorica.catalog.domain.CatalogPortion
import ru.calorica.catalog.domain.LeafAllocation
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.catalog.domain.Product
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

data class SearchKey(val tier: Int, val score: BigDecimal, val name: String, val id: UUID)
data class SearchHit(val product: Product, val key: SearchKey)

@Repository
class CatalogRepository(private val jdbc: JdbcClient) {
    private fun product(rs: ResultSet): Product {
        val nutrition = Nutrition(rs.getBigDecimal("manual_calories"), rs.getBigDecimal("manual_protein"),
            rs.getBigDecimal("manual_fat"), rs.getBigDecimal("manual_carbs"))
        return Product(rs.getObject("id", UUID::class.java), rs.getObject("owner_id", UUID::class.java),
            rs.getString("name"), rs.getBoolean("is_deleted"), nutrition, nutrition)
    }
    fun normalize(value: String): String = jdbc.sql("SELECT calorica.normalize_product_name(:value)")
        .param("value", value).query(String::class.java).single()

    fun search(owner: UUID, q: String, limit: Int, after: SearchKey?): List<SearchHit> {
        val prefix = q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        return jdbc.sql("""
            WITH matches AS (
                SELECT p.*, CASE WHEN :q = '' OR name_normalized = :q THEN 0
                    WHEN name_normalized LIKE :prefix ESCAPE '!' THEN 1 ELSE 2 END AS tier,
                    CASE WHEN :q = '' THEN 0::numeric ELSE similarity(name_normalized, :q)::numeric END AS score
                FROM calorica.products p
                WHERE NOT is_deleted AND (owner_id IS NULL OR owner_id = :owner)
                    AND (:q = '' OR name_normalized LIKE :prefix ESCAPE '!'
                        OR (length(:q) >= 3 AND name_normalized % :q AND similarity(name_normalized, :q) >= 0.3))
            )
            SELECT * FROM matches
            WHERE NOT :after OR (tier, -score, name_normalized COLLATE "C", id) >
                (:tier, -CAST(:score AS numeric), CAST(:lastName AS text) COLLATE "C", CAST(:lastId AS uuid))
            ORDER BY tier, score DESC, name_normalized COLLATE "C", id
            LIMIT :limit
        """).param("q", q).param("prefix", prefix).param("owner", owner).param("limit", limit)
            .param("after", after != null).param("tier", after?.tier ?: 0)
            .param("score", after?.score ?: BigDecimal.ZERO).param("lastName", after?.name ?: "")
            .param("lastId", after?.id ?: UUID(0, 0))
            .query { rs, _ -> SearchHit(product(rs), SearchKey(rs.getInt("tier"), rs.getBigDecimal("score"),
                rs.getString("name_normalized"), rs.getObject("id", UUID::class.java))) }.list()
    }
    fun visible(owner: UUID, ids: Collection<UUID>, includeDeleted: Boolean): List<Product> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.sql("""SELECT * FROM calorica.products WHERE id IN (:ids)
            AND (owner_id IS NULL OR owner_id = :owner) AND (:history OR NOT is_deleted)""")
            .param("ids", ids).param("owner", owner).param("history", includeDeleted)
            .query { rs, _ -> product(rs) }.list()
    }
    fun insert(id: UUID, owner: UUID?, name: String, nutrition: Nutrition) {
        jdbc.sql("""INSERT INTO calorica.products
            (id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
            VALUES (:id, :owner, :name, :calories, :protein, :fat, :carbs)""")
            .param("id", id).param("owner", owner, java.sql.Types.OTHER).param("name", name)
            .param("calories", nutrition.calories).param("protein", nutrition.protein)
            .param("fat", nutrition.fat).param("carbs", nutrition.carbs).update()
    }
    fun update(id: UUID, owner: UUID?, name: String, nutrition: Nutrition): Int =
        jdbc.sql("""UPDATE calorica.products SET name = :name, manual_calories = :calories,
            manual_protein = :protein, manual_fat = :fat, manual_carbs = :carbs
            WHERE id = :id AND owner_id IS NOT DISTINCT FROM CAST(:owner AS uuid) AND NOT is_deleted""")
            .param("id", id).param("owner", owner, java.sql.Types.OTHER).param("name", name)
            .param("calories", nutrition.calories).param("protein", nutrition.protein)
            .param("fat", nutrition.fat).param("carbs", nutrition.carbs).update()
    fun softDelete(id: UUID, owner: UUID?): Int =
        jdbc.sql("""UPDATE calorica.products SET is_deleted = true
            WHERE id = :id AND owner_id IS NOT DISTINCT FROM CAST(:owner AS uuid) AND NOT is_deleted""")
            .param("id", id).param("owner", owner, java.sql.Types.OTHER).update()

    /** The leaf projection is extended by T09; all arithmetic remains in the authoritative SQL. */
    fun allocate(owner: UUID, portions: List<CatalogPortion>): List<LeafAllocation> {
        if (portions.isEmpty()) return emptyList()
        val values = portions.indices.joinToString(",") { "(:ordinal$it, CAST(:id$it AS uuid), CAST(:grams$it AS numeric))" }
        var statement = jdbc.sql("""
            WITH portions(root_ordinal, product_id, grams) AS (VALUES $values)
            SELECT root_ordinal, p.id AS leaf_id, sum(portions.grams) AS allocated_grams
            FROM portions JOIN calorica.products p ON p.id = portions.product_id
            WHERE p.owner_id IS NULL OR p.owner_id = :owner
            GROUP BY root_ordinal, p.id ORDER BY root_ordinal, p.id
        """).param("owner", owner)
        portions.forEachIndexed { i, portion ->
            statement = statement.param("ordinal$i", portion.rootOrdinal).param("id$i", portion.productId)
                .param("grams$i", portion.consumedGrams)
        }
        return statement.query { rs, _ -> LeafAllocation(rs.getInt("root_ordinal"),
            rs.getObject("leaf_id", UUID::class.java), rs.getBigDecimal("allocated_grams")) }.list()
    }
}
