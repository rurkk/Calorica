package ru.calorica.catalog.api.units

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import ru.calorica.catalog.application.units.CatalogUnits
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.CatalogIds
import ru.calorica.catalog.domain.units.UnitValues
import ru.calorica.platform.security.Actor

@RestController
@RequestMapping("/api/v1")
class UnitsController(private val units: CatalogUnits) {
    private fun actor(value: Actor?): Actor = value ?: throw CatalogFailure(401, "UNAUTHORIZED")

    @GetMapping("/products/{productId}/units")
    fun list(@AuthenticationPrincipal current: Actor?, @PathVariable productId: String) =
        units.list(actor(current), CatalogIds.parse(productId))

    @PutMapping("/admin/products/{productId}/units/ml")
    fun set(@AuthenticationPrincipal current: Actor?, @PathVariable productId: String, @RequestBody body: JsonNode): Any {
        if (!body.isObject || body.fieldNames().asSequence().any { it != "gramsPerUnit" }) CatalogFailure.badRequest()
        val field = body["gramsPerUnit"] ?: CatalogFailure.invalid("gramsPerUnit")
        if (field.isNull) CatalogFailure.invalid("gramsPerUnit")
        if (!field.isTextual) CatalogFailure.badRequest()
        return units.setMillilitres(actor(current), CatalogIds.parse(productId), UnitValues.factor(field.textValue()))
    }

    @DeleteMapping("/admin/products/{productId}/units/ml")
    fun delete(@AuthenticationPrincipal current: Actor?, @PathVariable productId: String): ResponseEntity<Void> {
        units.deleteMillilitres(actor(current), CatalogIds.parse(productId))
        return ResponseEntity.noContent().build()
    }
}
