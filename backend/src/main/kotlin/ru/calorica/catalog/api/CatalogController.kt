package ru.calorica.catalog.api

import com.fasterxml.jackson.databind.JsonNode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import ru.calorica.catalog.application.CatalogService
import ru.calorica.catalog.domain.CatalogIds
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.catalog.domain.NutritionValues
import ru.calorica.catalog.domain.Product
import ru.calorica.catalog.domain.ProductNames
import ru.calorica.catalog.domain.CompositionWrite
import ru.calorica.platform.api.ApiProblems
import ru.calorica.platform.security.Actor
import java.sql.SQLException

object CatalogInput {
    private fun fields(node: JsonNode, allowed: Set<String>) {
        if (!node.isObject || node.fieldNames().asSequence().any { it !in allowed }) CatalogFailure.badRequest()
    }
    private fun text(node: JsonNode, field: String): String {
        if (node.isNull) CatalogFailure.invalid(field)
        if (!node.isTextual) CatalogFailure.badRequest()
        return node.textValue()
    }
    private fun nutrition(node: JsonNode): Nutrition {
        if (node.isNull) CatalogFailure.invalid("manualNutrition")
        val names = listOf("calories", "protein", "fat", "carbs")
        fields(node, names.toSet())
        val numbers = names.map { name ->
            val value = node[name] ?: CatalogFailure.invalid("manualNutrition.$name")
            NutritionValues.parse(text(value, "manualNutrition.$name"), "manualNutrition.$name")
        }
        return Nutrition(numbers[0], numbers[1], numbers[2], numbers[3])
    }
    fun create(node: JsonNode): Pair<String, Nutrition> {
        fields(node, setOf("name", "manualNutrition", "composition"))
        if (node.has("composition")) CatalogFailure.invalid("composition", "INVALID_COMPOSITION")
        val name = node["name"] ?: CatalogFailure.invalid("name")
        val nutrition = node["manualNutrition"] ?: CatalogFailure.invalid("manualNutrition")
        return ProductNames.name(text(name, "name")) to nutrition(nutrition)
    }
    fun patch(node: JsonNode): Pair<String?, Nutrition?> {
        fields(node, setOf("name", "manualNutrition"))
        return node["name"]?.let { ProductNames.name(text(it, "name")) } to node["manualNutrition"]?.let(::nutrition)
    }
    fun createComposite(node: JsonNode): Pair<String, CompositionWrite> {
        fields(node, setOf("name", "composition"))
        val name = node["name"] ?: CatalogFailure.invalid("name")
        val composition = node["composition"] ?: CatalogFailure.invalid("composition", "INVALID_COMPOSITION")
        return ProductNames.name(text(name, "name")) to CompositionInput.parse(composition)
    }
}

@RestController
@RequestMapping("/api/v1")
class CatalogController(private val catalog: CatalogService) {
    private fun actor(value: Actor?): Actor = value ?: throw CatalogFailure(401, "UNAUTHORIZED")
    @GetMapping("/products")
    fun search(@AuthenticationPrincipal current: Actor?, @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "20") limit: Int, @RequestParam(required = false) cursor: String?) =
        catalog.search(actor(current), q, limit, cursor)
    @GetMapping("/products/{productId}")
    fun get(@AuthenticationPrincipal current: Actor?, @PathVariable productId: String) = catalog.get(actor(current), CatalogIds.parse(productId))
    @PostMapping("/products", "/admin/products")
    fun create(@AuthenticationPrincipal current: Actor?, @RequestBody body: JsonNode, request: HttpServletRequest): ResponseEntity<Product> {
        if (body.has("composition")) {
            val input = CatalogInput.createComposite(body)
            return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createComposition(actor(current), input.first, input.second,
                request.servletPath.startsWith("/api/v1/admin/")))
        }
        val input = CatalogInput.create(body)
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.create(actor(current), input.first, input.second,
            request.servletPath.startsWith("/api/v1/admin/")))
    }
    @PutMapping("/products/{productId}/composition", "/admin/products/{productId}/composition")
    fun composition(@AuthenticationPrincipal current: Actor?, @PathVariable productId: String, @RequestBody body: JsonNode,
        request: HttpServletRequest): Product = catalog.writeComposition(actor(current), CatalogIds.parse(productId),
            CompositionInput.parse(body), request.servletPath.startsWith("/api/v1/admin/"))
    @PatchMapping("/products/{productId}", "/admin/products/{productId}")
    fun patch(@AuthenticationPrincipal current: Actor?, @PathVariable productId: String, @RequestBody body: JsonNode,
        request: HttpServletRequest): Product {
        val input = CatalogInput.patch(body)
        return catalog.patch(actor(current), CatalogIds.parse(productId), input.first, input.second, request.servletPath.startsWith("/api/v1/admin/"))
    }
    @DeleteMapping("/products/{productId}", "/admin/products/{productId}")
    fun delete(@AuthenticationPrincipal current: Actor?, @PathVariable productId: String, @RequestParam mode: String,
        request: HttpServletRequest): ResponseEntity<Void> {
        if (mode !in setOf("KEEP_REFERENCES", "CASCADE")) CatalogFailure.badRequest()
        catalog.delete(actor(current), CatalogIds.parse(productId), mode, request.servletPath.startsWith("/api/v1/admin/"))
        return ResponseEntity.noContent().build()
    }
}

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = ["ru.calorica.catalog.api"])
class CatalogExceptionHandler(private val problems: ApiProblems) {
    @ExceptionHandler(CatalogFailure::class)
    fun failure(error: CatalogFailure, request: HttpServletRequest): ResponseEntity<Any> {
        val problem = problems.problem(error.status, request).apply {
            setProperty("code", error.code)
            if (error.errors.isNotEmpty()) setProperty("errors", error.errors)
        }
        return ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }
    @ExceptionHandler(DataAccessException::class)
    fun database(error: DataAccessException, request: HttpServletRequest): ResponseEntity<Any> {
        val state = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState
        val status = if (error is DataAccessResourceFailureException || error is QueryTimeoutException ||
            error is TransientDataAccessResourceException || state in setOf("55P03", "57014", "08000", "08001", "08003", "08006", "08007", "57P01")) 503 else 500
        return failure(CatalogFailure(status, if (status == 503) "SERVICE_UNAVAILABLE" else "INTERNAL_SERVER_ERROR"), request)
    }
}
