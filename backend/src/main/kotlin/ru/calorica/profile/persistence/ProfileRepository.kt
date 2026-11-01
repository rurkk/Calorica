package ru.calorica.profile.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.calorica.profile.domain.Profile
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

@Repository
class ProfileRepository(private val jdbc: JdbcClient) {
    fun create(id: UUID) { jdbc.sql("INSERT INTO calorica.profiles(account_id) VALUES (:id)").param("id", id).update() }
    fun get(id: UUID): Profile = jdbc.sql("SELECT * FROM calorica.profiles WHERE account_id=:id")
        .param("id", id).query { rs, _ -> map(rs) }.single()
    fun patch(id: UUID, changes: Map<String, BigDecimal?>): Profile {
        if (changes.isEmpty()) return get(id)
        // Keys come only from the closed field mapping in ProfileService.
        val assignments = changes.keys.joinToString(", ") { "$it = :$it" }
        var statement = jdbc.sql("UPDATE calorica.profiles SET $assignments WHERE account_id=:id RETURNING *").param("id", id)
        for ((field, value) in changes) statement = statement.param(field, value, java.sql.Types.NUMERIC)
        return statement.query { rs, _ -> map(rs) }.single()
    }
    private fun map(rs: ResultSet) = Profile(rs.getBigDecimal("calorie_target")?.toPlainString(),
        rs.getBigDecimal("protein_target")?.toPlainString(), rs.getBigDecimal("fat_target")?.toPlainString(),
        rs.getBigDecimal("carbs_target")?.toPlainString())
}
