package ru.calorica.catalog.api.labels

import com.fasterxml.jackson.databind.JsonNode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import ru.calorica.catalog.application.labels.ProductLabels
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.CatalogIds
import ru.calorica.catalog.domain.ProductNames
import ru.calorica.catalog.domain.labels.ProductLabelValues
import ru.calorica.platform.security.Actor
import java.util.UUID

object ProductLabelInput {
    private fun field(node: JsonNode, name: String): JsonNode {
        if (!node.isObject || node.fieldNames().asSequence().any { it != name }) CatalogFailure.badRequest()
        val value = node[name] ?: CatalogFailure.invalid(name)
        if (value.isNull) CatalogFailure.invalid(name)
        return value
    }
    fun name(node: JsonNode): String {
        val value = field(node, "name")
        if (!value.isTextual) CatalogFailure.badRequest()
        return ProductNames.name(value.textValue())
    }
    fun assignment(node: JsonNode): List<UUID> {
        val value = field(node, "labelIds")
        if (!value.isArray) CatalogFailure.badRequest()
        return ProductLabelValues.assignment(value.map {
            if (!it.isTextual) CatalogFailure.badRequest()
            CatalogIds.parse(it.textValue())
        })
    }
}

@RestController
@RequestMapping("/api/v1")
class ProductLabelsController(private val labels: ProductLabels) {
    private fun actor(value: Actor?): Actor = value ?: throw CatalogFailure(401, "UNAUTHORIZED")
    @GetMapping("/product-labels")
    fun list(@AuthenticationPrincipal current: Actor?): Any { actor(current); return labels.list() }
    @PostMapping("/admin/product-labels")
    fun create(@AuthenticationPrincipal current: Actor?, @RequestBody body: JsonNode) =
        ResponseEntity.status(HttpStatus.CREATED).body(labels.create(actor(current), ProductLabelInput.name(body)))
    @PatchMapping("/admin/product-labels/{labelId}")
    fun rename(@AuthenticationPrincipal current: Actor?, @PathVariable labelId: String, @RequestBody body: JsonNode) =
        labels.rename(actor(current), CatalogIds.parse(labelId), ProductLabelInput.name(body))
    @DeleteMapping("/admin/product-labels/{labelId}")
    fun delete(@AuthenticationPrincipal current: Actor?, @PathVariable labelId: String): ResponseEntity<Void> {
        labels.delete(actor(current), CatalogIds.parse(labelId))
        return ResponseEntity.noContent().build()
    }
    @PutMapping("/products/{productId}/labels", "/admin/products/{productId}/labels")
    fun assign(@AuthenticationPrincipal current: Actor?, @PathVariable productId: String, @RequestBody body: JsonNode,
        request: HttpServletRequest) = labels.assign(actor(current), CatalogIds.parse(productId), ProductLabelInput.assignment(body),
        request.servletPath.startsWith("/api/v1/admin/"))
}
