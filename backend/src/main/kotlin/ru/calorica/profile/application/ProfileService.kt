package ru.calorica.profile.application

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.security.Actor
import ru.calorica.profile.domain.Profile
import ru.calorica.profile.persistence.ProfileRepository
import java.math.BigDecimal

@Service
class ProfileService(private val profiles: ProfileRepository) {
    @Transactional(timeout = 8)
    fun patch(actor: Actor, body: JsonNode): Profile {
        if (!body.isObject || body.fieldNames().asSequence().any { it !in FIELDS }) throw ApiFailure(400, "BAD_REQUEST")
        val changes = mutableMapOf<String, BigDecimal?>()
        for ((field, column) in FIELDS) {
            if (!body.has(field)) continue
            val node = body[field]
            if (!node.isNull && !node.isTextual) throw ApiFailure(400, "BAD_REQUEST")
            if (node.isNull) { changes[column] = null; continue }
            if (!DECIMAL.matches(node.textValue())) throw ApiFailure.invalid(field)
            changes[column] = node.textValue().toBigDecimal()
        }
        return profiles.patch(actor.id, changes)
    }
    companion object {
        private val DECIMAL = Regex("^(0|[1-9][0-9]{0,4})(\\.[0-9]{1,2})?$")
        private val FIELDS = linkedMapOf("calorieTarget" to "calorie_target", "proteinTarget" to "protein_target",
            "fatTarget" to "fat_target", "carbsTarget" to "carbs_target")
    }
}
