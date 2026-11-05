package ru.calorica.catalog.persistence

import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.calorica.catalog.application.CalculatedNutrition
import ru.calorica.catalog.domain.*
import java.math.BigDecimal
import java.sql.SQLException
import java.util.UUID

/** SQL owns all graph arithmetic. Kotlin only parses values and enforces output bounds. */
@Repository
class CompositionRepository(private val jdbc: JdbcClient) {
    fun components(ids: Collection<UUID>): Map<UUID, List<ProductComponent>> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql("""SELECT parent_id,child_id,fraction,yield_factor FROM calorica.product_components
            WHERE parent_id IN (:ids) ORDER BY parent_id,child_id""").param("ids", ids)
            .query { rs, _ -> rs.getObject("parent_id", UUID::class.java) to ProductComponent(
                rs.getObject("child_id", UUID::class.java), rs.getBigDecimal("fraction"), rs.getBigDecimal("yield_factor")) }
            .list().groupBy({ it.first }, { it.second })
    }
    fun replace(id: UUID, children: List<ProductComponent>, manual: BigDecimal?) {
        jdbc.sql("DELETE FROM calorica.product_components WHERE parent_id=:id").param("id",id).update()
        children.forEach { child ->
            jdbc.sql("""INSERT INTO calorica.product_components(parent_id,child_id,fraction,yield_factor)
                VALUES (:parent,:child,:fraction,:yield)""")
                .param("parent", id).param("child", child.productId).param("fraction", child.fraction)
                .param("yield", child.yieldFactor, java.sql.Types.NUMERIC).update()
        }
        jdbc.sql("UPDATE calorica.products SET manual_yield_factor=:yield WHERE id=:id")
            .param("yield", manual, java.sql.Types.NUMERIC).param("id", id).update()
    }
    private fun bounded(value: BigDecimal): BigDecimal {
        if (value.scale() > 1000 || value.precision() - value.scale() > 100)
            CatalogFailure.invalid("calculation", "CALCULATION_LIMIT")
        return value
    }
    private fun <T> query(block: () -> T): T = try { block() } catch (error: DataAccessException) {
        val state = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState
        when (state) {
            "PCC01" -> throw CatalogFailure(409, "CYCLE_DETECTED")
            "PCL01", "22003" -> CatalogFailure.invalid("calculation", "CALCULATION_LIMIT")
            "PCN01" -> CatalogFailure.notFound()
            "PCG01" -> CatalogFailure.invalid("composition", "INVALID_COMPOSITION")
            "57014" -> throw CatalogFailure(503, "CALCULATION_TIMEOUT")
            else -> throw error
        }
    }
    fun calculate(owner: UUID, ids: Collection<UUID>): Map<UUID, CalculatedNutrition> {
        if (ids.isEmpty()) return emptyMap()
        return query {
            jdbc.sql("SELECT * FROM calorica.catalog_nutrition(ARRAY[:ids]::uuid[], :owner)")
                .param("ids", ids.toSet()).param("owner", owner).query { rs, _ ->
                rs.getObject("root_id", UUID::class.java) to CalculatedNutrition(Nutrition(
                    bounded(rs.getBigDecimal("calories")), bounded(rs.getBigDecimal("protein")),
                    bounded(rs.getBigDecimal("fat")), bounded(rs.getBigDecimal("carbs"))),
                    bounded(rs.getBigDecimal("effective_yield_factor")), rs.getString("yield_source"))
            }.list().toMap()
        }
    }
    fun allocate(owner: UUID, portions: List<CatalogPortion>): List<LeafAllocation> {
        if (portions.isEmpty()) return emptyList()
        val values = portions.indices.joinToString(",") { "(${it + 1}, :ordinal$it, CAST(:id$it AS uuid), CAST(:grams$it AS numeric))" }
        return query {
            var statement = jdbc.sql("""
                WITH portions(input_index,root_ordinal,product_id,grams) AS (VALUES $values),
                paths AS MATERIALIZED (SELECT * FROM calorica.catalog_paths(ARRAY(SELECT product_id FROM portions ORDER BY input_index), :owner))
                SELECT portions.root_ordinal, t.node_id AS leaf_id, sum(portions.grams*t.allocation_weight) AS allocated_grams
                FROM portions JOIN paths t ON t.root_ordinal=portions.input_index WHERE t.is_leaf
                GROUP BY portions.root_ordinal,t.node_id ORDER BY portions.root_ordinal,t.node_id
            """).param("owner",owner)
            portions.forEachIndexed { i,p -> statement = statement.param("ordinal$i",p.rootOrdinal).param("id$i",p.productId).param("grams$i",p.consumedGrams) }
            statement.query { rs, _ -> LeafAllocation(rs.getInt("root_ordinal"),rs.getObject("leaf_id",UUID::class.java),
                bounded(rs.getBigDecimal("allocated_grams"))) }.list()
        }
    }
}
