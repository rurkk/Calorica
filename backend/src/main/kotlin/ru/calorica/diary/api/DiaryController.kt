package ru.calorica.diary.api

import com.fasterxml.jackson.databind.JsonNode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.CatalogIds
import ru.calorica.diary.application.DiaryService
import ru.calorica.diary.domain.*
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.api.ApiProblems
import ru.calorica.platform.api.FieldFailure
import ru.calorica.platform.security.Actor
import java.sql.SQLException
import java.util.UUID

object DiaryInput {
    private val allowed = setOf("productId", "quantity", "unit", "occurredAt", "mealLabelIds")
    private fun fields(node: JsonNode) {
        if (!node.isObject || node.fieldNames().asSequence().any { it !in allowed }) DiaryValues.badRequest()
    }
    private fun text(node: JsonNode, field: String): String {
        if (node.isNull) DiaryValues.invalid(field)
        if (!node.isTextual) DiaryValues.badRequest()
        return node.textValue()
    }
    private fun labels(node: JsonNode): List<UUID> {
        if (node.isNull) DiaryValues.invalid("mealLabelIds")
        if (!node.isArray) DiaryValues.badRequest()
        if (node.size() > 4) DiaryValues.invalid("mealLabelIds")
        return DiaryValues.labels(node.map { CatalogIds.parse(text(it, "mealLabelIds")) })
    }
    private fun unit(node: JsonNode): String = text(node, "unit").also {
        if (it !in setOf("g", "ml")) DiaryValues.invalid("unit", "UNIT_UNAVAILABLE")
    }
    fun create(node: JsonNode): DiaryWrite {
        fields(node)
        fun required(name: String) = node[name] ?: DiaryValues.invalid(name)
        return DiaryWrite(CatalogIds.parse(text(required("productId"), "productId")),
            DiaryValues.quantity(text(required("quantity"), "quantity")), unit(required("unit")),
            DiaryValues.occurredAt(text(required("occurredAt"), "occurredAt")), labels(required("mealLabelIds")))
    }
    fun patch(node: JsonNode): DiaryPatch {
        fields(node)
        if (node.has("quantity") != node.has("unit")) DiaryValues.invalid("quantity")
        return DiaryPatch(node["productId"]?.let { CatalogIds.parse(text(it, "productId")) },
            node["quantity"]?.let { DiaryValues.quantity(text(it, "quantity")) }, node["unit"]?.let(::unit),
            node["occurredAt"]?.let { DiaryValues.occurredAt(text(it, "occurredAt")) }, node["mealLabelIds"]?.let(::labels))
    }
}

@RestController
@RequestMapping("/api/v1")
class DiaryController(private val diary: DiaryService) {
    private fun actor(value: Actor?) = value ?: throw ApiFailure.unauthorized()
    @GetMapping("/meal-labels")
    fun labels(@AuthenticationPrincipal current: Actor?): List<MealLabel> { actor(current); return diary.labels() }
    @GetMapping("/diary")
    fun day(@AuthenticationPrincipal current: Actor?, @RequestParam date: String, @RequestParam zone: String) =
        diary.day(actor(current), date, zone)
    @GetMapping("/diary/entries/{entryId}")
    fun get(@AuthenticationPrincipal current: Actor?, @PathVariable entryId: String) = diary.get(actor(current), CatalogIds.parse(entryId))
    @PostMapping("/diary/entries")
    fun create(@AuthenticationPrincipal current: Actor?, @RequestBody body: JsonNode): ResponseEntity<DiaryEntry> =
        ResponseEntity.status(HttpStatus.CREATED).body(diary.create(actor(current), DiaryInput.create(body)))
    @PatchMapping("/diary/entries/{entryId}")
    fun patch(@AuthenticationPrincipal current: Actor?, @PathVariable entryId: String, @RequestBody body: JsonNode) =
        diary.patch(actor(current), CatalogIds.parse(entryId), DiaryInput.patch(body))
    @DeleteMapping("/diary/entries/{entryId}")
    fun delete(@AuthenticationPrincipal current: Actor?, @PathVariable entryId: String): ResponseEntity<Void> {
        diary.delete(actor(current), CatalogIds.parse(entryId)); return ResponseEntity.noContent().build()
    }
}

/** Catalog capabilities preserve their protocol failures when consumed by diary and analytics controllers. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = ["ru.calorica.diary.api", "ru.calorica.analytics.api"])
class DiaryExceptionHandler(private val problems: ApiProblems) {
    @ExceptionHandler(CatalogFailure::class)
    fun catalog(error: CatalogFailure, request: HttpServletRequest): ResponseEntity<Any> =
        ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problems.problem(error.status, request, error.code, error.errors.map { FieldFailure(it.field, it.code) }))
    @ExceptionHandler(DataAccessException::class)
    fun database(error: DataAccessException, request: HttpServletRequest): ResponseEntity<Any> {
        val state = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState
        val status = when {
            state == "22003" -> 422
            error is DataAccessResourceFailureException || error is QueryTimeoutException || error is TransientDataAccessResourceException ||
                state in setOf("55P03", "57014", "08000", "08001", "08003", "08006", "08007", "57P01") -> 503
            else -> 500
        }
        val code = when (status) { 422 -> "CALCULATION_LIMIT"; 503 -> "SERVICE_UNAVAILABLE"; else -> "INTERNAL_SERVER_ERROR" }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problems.problem(status, request, code))
    }
}
