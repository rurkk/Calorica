package ru.calorica.platform.persistence

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

@Component("schemaHealthIndicator")
class SchemaHealthIndicator(private val jdbc: JdbcClient) : HealthIndicator {
    override fun health(): Health = try {
        val exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = :schema)")
            .param("schema", "calorica").query(Boolean::class.java).single()
        if (exists) Health.up().build() else Health.down().build()
    } catch (_: Exception) {
        Health.down().build()
    }
}
