package ru.calorica.catalog.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class CatalogLockSql(private val jdbc: JdbcClient) {
    fun limits() {
        jdbc.sql("SET LOCAL statement_timeout = '5s'").update()
        jdbc.sql("SET LOCAL lock_timeout = '2s'").update()
        jdbc.sql("SET LOCAL pg_trgm.similarity_threshold = 0.3").update()
    }
    fun sharedBase() { jdbc.sql("SELECT pg_advisory_xact_lock_shared(1128352847, 0)").query { _, _ -> 0 }.single() }
    fun exclusiveBase() { jdbc.sql("SELECT pg_advisory_xact_lock(1128352847, 0)").query { _, _ -> 0 }.single() }
    fun owner(id: UUID) {
        jdbc.sql("SELECT pg_advisory_xact_lock(1128352848, hashtext(:id))")
            .param("id", id.toString()).query { _, _ -> 0 }.single()
    }
}
