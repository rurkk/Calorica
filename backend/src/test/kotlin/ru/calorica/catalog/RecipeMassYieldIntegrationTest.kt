package ru.calorica.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import org.springframework.jdbc.core.simple.JdbcClient
import ru.calorica.accounts.application.AccountsService
import ru.calorica.accounts.domain.Credentials
import ru.calorica.catalog.api.CompositionInput
import ru.calorica.catalog.application.*
import ru.calorica.catalog.domain.*
import ru.calorica.platform.security.Actor
import ru.calorica.platform.security.Role
import java.math.BigDecimal
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecipeMassYieldIntegrationTest @Autowired constructor(
    private val catalog: CatalogService, private val transactions: CatalogTransactions,
    private val locks: CatalogMutationLock, private val jdbc: JdbcClient,
    private val accounts: AccountsService, private val http: TestRestTemplate, private val mapper: ObjectMapper,
) {
    private lateinit var actor: Actor
    private lateinit var token: String
    @BeforeAll fun setup() {
        val user = accounts.register(Credentials("recipe-${UUID.randomUUID()}@example.org", "recipe test password8"))
        val session = jdbc.sql("SELECT id FROM calorica.sessions WHERE account_id=:id")
            .param("id", user.account.id).query(UUID::class.java).single()
        actor = Actor(user.account.id, Role.USER, session); token = user.accessToken
    }
    private fun leaf(calories: String = "400") = catalog.create(actor, "Recipe-${UUID.randomUUID()}",
        Nutrition(calories.toBigDecimal(), BigDecimal("10"), BigDecimal("2"), BigDecimal("3")))
    private fun component(id: UUID, amount: String, mode: String = "GRAMS", factor: String? = null, present: Boolean = false) =
        mutableMapOf<String, Any?>("productId" to id.toString(), (if (mode == "GRAMS") "grams" else "percent") to amount).apply {
            if (present || factor != null) put("yieldFactor", factor)
        }
    private fun input(children: List<Map<String, Any?>>, mode: String = "GRAMS", vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("inputMode" to mode, "components" to children) + extra.toMap()
    private fun parse(input: Map<String, Any?>) = CompositionInput.parse(mapper.valueToTree(input))
    private fun create(input: Map<String, Any?>) = catalog.createComposition(actor, "Recipe-${UUID.randomUUID()}", parse(input))
    private fun write(id: UUID, input: Map<String, Any?>) = catalog.writeComposition(actor, id, parse(input))
    private fun request(method: HttpMethod, path: String, body: Any? = null, expected: Int = 200): JsonNode {
        val headers = HttpHeaders().apply { setBearerAuth(token); contentType = MediaType.APPLICATION_JSON }
        val result = http.exchange("/api/v1$path", method, HttpEntity(body?.let(mapper::writeValueAsString), headers), String::class.java)
        assertThat(result.statusCode.value()).describedAs("%s %s: %s", method, path, result.body).isEqualTo(expected)
        return mapper.readTree(result.body)
    }
    private fun allocations(id: UUID, grams: String = "200") = transactions.snapshot {
        catalog.allocateLeaves(actor, listOf(CatalogPortion(7, id, grams.toBigDecimal())), it).associate { row -> row.leafId to row.allocatedGrams }
    }
    private fun failure(code: String, block: () -> Unit) = assertThatThrownBy(block)
        .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo(code) }

    @Test fun `GRAMS POST persists exact fractions and repeated percent edits never lose the remainder`() {
        val children = List(3) { leaf() }.sortedBy { it.id.toString() }
        val created = request(HttpMethod.POST, "/products", mapOf("name" to "Equal masses", "composition" to
            input(children.reversed().map { component(it.id, "1.000") })), 201)
        val id = UUID.fromString(created["id"].asText())
        repeat(3) {
            val current = catalog.get(actor, id)
            assertThat(current.components.map { it.fraction.toPlainString() })
                .containsExactly("0.333333333334", "0.333333333333", "0.333333333333")
            val percent = input(current.components.reversed().map {
                component(it.productId, it.fraction.movePointRight(2).stripTrailingZeros().toPlainString(), "PERCENT")
            }, "PERCENT")
            request(HttpMethod.PUT, "/products/$id/composition", percent)
        }
        val sum = jdbc.sql("SELECT sum(fraction) FROM calorica.product_components WHERE parent_id=:id")
            .param("id", id).query(BigDecimal::class.java).single()
        assertThat(sum).isEqualByComparingTo("1")
        val columns = jdbc.sql("""SELECT column_name FROM information_schema.columns
            WHERE table_schema='calorica' AND table_name='product_components'""").query(String::class.java).list()
        assertThat(columns).containsExactlyInAnyOrder("parent_id", "child_id", "fraction", "yield_factor")
        val productColumns = jdbc.sql("""SELECT column_name FROM information_schema.columns
            WHERE table_schema='calorica' AND table_name='products'""").query(String::class.java).list()
        assertThat(productColumns).noneMatch { it.contains("grams") || it.contains("mass") || it.contains("effective_yield") }
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.diary_entries WHERE product_id=:id")
            .param("id", id).query(Int::class.java).single()).isZero()
    }

    @Test fun `output weight uses actual input sum and manual setting survives both input modes until explicitly changed`() {
        val a = leaf(); val b = leaf("80")
        val p = create(input(listOf(component(a.id, "100"), component(b.id, "200")), "GRAMS", "outputGrams" to "240"))
        assertThat(p.manualYieldFactor!!.toBigDecimal()).isEqualByComparingTo("0.8")
        var result = write(p.id, input(listOf(component(a.id, "3"), component(b.id, "2"))))
        assertThat(result.manualYieldFactor!!.toBigDecimal()).isEqualByComparingTo("0.8")
        assertThat(result.nutritionPer100g.calories).isEqualByComparingTo("340")
        assertThat(allocations(p.id)[a.id]).isEqualByComparingTo("120")
        assertThat(allocations(p.id)[b.id]).isEqualByComparingTo("80")
        result = write(p.id, input(listOf(component(a.id, "60", "PERCENT"), component(b.id, "40", "PERCENT")), "PERCENT"))
        assertThat(result.manualYieldFactor!!.toBigDecimal()).isEqualByComparingTo("0.8")
        result = write(p.id, input(listOf(component(a.id, "3"), component(b.id, "2")), "GRAMS", "outputGrams" to "10"))
        assertThat(result.manualYieldFactor!!.toBigDecimal()).isEqualByComparingTo("2")
        assertThat(result.nutritionPer100g.calories).isEqualByComparingTo("136")
        assertThat(allocations(p.id)[a.id]).isEqualByComparingTo("120")
        result = write(p.id, input(listOf(component(a.id, "3"), component(b.id, "2")), "GRAMS", "manualYieldFactor" to null))
        assertThat(result.yieldSource).isEqualTo("DEFAULT")
        val halfUp = create(input(listOf(component(a.id, "4096")), "GRAMS", "outputGrams" to "0.001"))
        assertThat(halfUp.manualYieldFactor).isEqualTo("0.000000244141")
    }

    @Test fun `same total ingredient yield keeps nutrition while ready grams change and NULL differs from one`() {
        val a = leaf(); val b = leaf("80")
        val p = create(input(listOf(component(a.id, "1", factor = "0.5"), component(b.id, "1", factor = "1.5"))))
        assertThat(p.effectiveYieldFactor).isEqualTo("1")
        assertThat(p.nutritionPer100g.calories).isEqualByComparingTo("240")
        assertThat(p.manualYieldFactor).isNull()
        assertThat(allocations(p.id)[a.id]).isEqualByComparingTo("50")
        assertThat(allocations(p.id)[b.id]).isEqualByComparingTo("150")
        var changed = write(p.id, input(listOf(component(a.id, "1", factor = "1.5"), component(b.id, "1", factor = "0.5"))))
        assertThat(changed.nutritionPer100g).isEqualTo(p.nutritionPer100g)
        assertThat(allocations(p.id)[a.id]).isEqualByComparingTo("150")
        assertThat(allocations(p.id)[b.id]).isEqualByComparingTo("50")
        changed = write(p.id, input(listOf(component(a.id, "1", factor = "0.5"), component(b.id, "1", present = true))))
        assertThat(changed.effectiveYieldFactor).isEqualTo("0.75")
        assertThat(changed.nutritionPer100g.calories).isEqualByComparingTo("320")
        assertThat(changed.components.single { it.productId == b.id }.yieldFactor).isNull()
        changed = write(p.id, input(listOf(component(a.id, "1", factor = "1"), component(b.id, "1"))))
        assertThat(changed.yieldSource).isEqualTo("INGREDIENTS")
        assertThat(changed.effectiveYieldFactor).isEqualTo("1")
        changed = write(p.id, input(listOf(component(a.id, "1", present = true), component(b.id, "1"))))
        assertThat(changed.yieldSource).isEqualTo("DEFAULT")
        assertThat(changed.effectiveYieldFactor).isEqualTo("1")
    }

    @Test fun `derived ingredient yield retains more than twelve decimal places and is never persisted`() {
        val a = leaf(); val b = leaf("80")
        val p = create(input(listOf(component(a.id, "33.3333333333", "PERCENT", "0.333333333333"),
            component(b.id, "66.6666666667", "PERCENT")), "PERCENT"))
        assertThat(p.effectiveYieldFactor.toBigDecimal()).isEqualByComparingTo("0.777777777777888888888889")
        assertThat(p.manualYieldFactor).isNull()
        assertThat(jdbc.sql("SELECT manual_yield_factor FROM calorica.products WHERE id=:id").param("id", p.id)
            .query { rs, _ -> rs.getBigDecimal(1) }.list().single()).isNull()
    }

    @Test fun `percentage only client preserves hidden factors and changed shares recompute yield and ready grams`() {
        val a = leaf(); val b = leaf("80")
        val p = create(input(listOf(component(a.id, "1", factor = "0.5"), component(b.id, "1", factor = "1.5"))))
        request(HttpMethod.PATCH, "/products/${p.id}", mapOf("name" to "Edited recipe"))
        val result = request(HttpMethod.PUT, "/products/${p.id}/composition",
            input(listOf(component(b.id, "25", "PERCENT"), component(a.id, "75", "PERCENT")), "PERCENT"))
        assertThat(result["yieldSource"].asText()).isEqualTo("INGREDIENTS")
        assertThat(result["effectiveYieldFactor"].asText().toBigDecimal()).isEqualByComparingTo("0.75")
        val current = catalog.get(actor, p.id)
        assertThat(current.components.single { it.productId == a.id }.yieldFactor).isEqualByComparingTo("0.5")
        assertThat(current.components.single { it.productId == b.id }.yieldFactor).isEqualByComparingTo("1.5")
        assertThat(allocations(p.id)[a.id]).isEqualByComparingTo("100")
        assertThat(allocations(p.id)[b.id]).isEqualByComparingTo("100")
    }

    @Test fun `nutrition and ready gram allocations remain in one snapshot during ingredient yield update`() {
        val a = leaf(); val b = leaf("80")
        val p = create(input(listOf(component(a.id, "1", factor = "0.5"), component(b.id, "1", factor = "1.5"))))
        val pool = Executors.newSingleThreadExecutor()
        try {
            transactions.snapshot { snapshot ->
                assertThat(catalog.nutrition(actor, listOf(p.id), snapshot).getValue(p.id).effectiveYieldFactor).isEqualByComparingTo("1")
                pool.submit { write(p.id, input(listOf(component(a.id, "1"), component(b.id, "1", present = true)))) }
                    .get(3, TimeUnit.SECONDS)
                val values = catalog.nutrition(actor, listOf(p.id), snapshot).getValue(p.id)
                assertThat(values.nutritionPer100g.calories).isEqualByComparingTo("240")
                val rows = catalog.allocateLeaves(actor, listOf(CatalogPortion(0, p.id, BigDecimal("200"))), snapshot)
                assertThat(rows.single { it.leafId == a.id }.allocatedGrams).isEqualByComparingTo("50")
                assertThat(rows.single { it.leafId == b.id }.allocatedGrams).isEqualByComparingTo("150")
                true
            }
            assertThat(catalog.get(actor, p.id).nutritionPer100g.calories).isEqualByComparingTo("320")
        } finally { pool.shutdownNow() }
    }

    @Test fun `nested output and edge processing have separate nutrient and ready gram weights across common paths`() {
        val a = leaf(); val b = leaf("80")
        val child = create(input(listOf(component(a.id, "100"), component(b.id, "100")), "GRAMS", "outputGrams" to "100"))
        assertThat(child.nutritionPer100g.calories).isEqualByComparingTo("480")
        val parent = create(input(listOf(component(child.id, "1", factor = "0.5"), component(a.id, "1", factor = "1.5"))))
        assertThat(parent.nutritionPer100g.calories).isEqualByComparingTo("440")
        assertThat(allocations(parent.id)[a.id]).isEqualByComparingTo("175")
        assertThat(allocations(parent.id)[b.id]).isEqualByComparingTo("25")
        val rows = transactions.snapshot { catalog.allocateLeaves(actor, listOf(
            CatalogPortion(10, parent.id, BigDecimal("200")), CatalogPortion(20, parent.id, BigDecimal("100"))), it) }
        assertThat(rows.filter { it.rootOrdinal == 10 }.sumOf { it.allocatedGrams }).isEqualByComparingTo("200")
        assertThat(rows.filter { it.rootOrdinal == 20 }.sumOf { it.allocatedGrams }).isEqualByComparingTo("100")
        assertThat(rows.single { it.rootOrdinal == 20 && it.leafId == a.id }.allocatedGrams).isEqualByComparingTo("87.5")
    }

    @Test fun `hidden factors conflict atomically with new output and clearing last factor never restores old manual yield`() {
        val a = leaf(); val b = leaf("80")
        val p = create(input(listOf(component(a.id, "1"), component(b.id, "1")), "GRAMS", "outputGrams" to "1"))
        var result = write(p.id, input(listOf(component(a.id, "1", factor = "1"), component(b.id, "1"))))
        assertThat(result.manualYieldFactor).isNull()
        val before = catalog.get(actor, p.id)
        val bad = input(listOf(component(a.id, "2"), component(b.id, "1")), "GRAMS", "outputGrams" to "2")
        assertThat(request(HttpMethod.PUT, "/products/${p.id}/composition", bad, 409)["code"].asText()).isEqualTo("YIELD_MODE_CONFLICT")
        assertThat(catalog.get(actor, p.id)).isEqualTo(before)
        result = write(p.id, input(listOf(component(a.id, "1", present = true), component(b.id, "1"))))
        assertThat(result.yieldSource).isEqualTo("DEFAULT")
        assertThat(result.manualYieldFactor).isNull()
        write(p.id, input(listOf(component(a.id, "1", factor = "1"), component(b.id, "1"))))
        result = write(p.id, input(listOf(component(a.id, "1", present = true), component(b.id, "1")), "GRAMS", "outputGrams" to "1.6"))
        assertThat(result.manualYieldFactor!!.toBigDecimal()).isEqualByComparingTo("0.8")
        val leaf = write(p.id, input(emptyList(), "PERCENT", "manualNutrition" to
            mapOf("calories" to "75", "protein" to "1", "fat" to "2", "carbs" to "3")))
        assertThat(leaf.kind).isEqualTo("LEAF"); assertThat(leaf.manualYieldFactor).isNull()
        assertThat(leaf.effectiveYieldFactor).isEqualTo("1")
    }

    @Test fun `invalid GRAMS requests leave no product or changed composition after post insert validation`() {
        val a = leaf(); val p = create(input(listOf(component(a.id, "1"))))
        val before = catalog.get(actor, p.id)
        val count = jdbc.sql("SELECT count(*) FROM calorica.products WHERE owner_id=:owner")
            .param("owner", actor.id).query(Int::class.java).single()
        val badInputs = listOf(
            input(listOf(component(a.id, "1", factor = "1")), "GRAMS", "outputGrams" to "1") to "YIELD_MODE_CONFLICT",
            input(listOf(component(UUID.randomUUID(), "1")), "GRAMS", "outputGrams" to "0.8") to "NOT_FOUND")
        badInputs.forEach { (body, code) ->
            failure(code) { create(body) }
            failure(code) { write(p.id, body) }
            assertThat(catalog.get(actor, p.id)).isEqualTo(before)
        }
        failure("CYCLE_DETECTED") { write(p.id, input(listOf(component(p.id, "1")), "GRAMS", "outputGrams" to "0.8")) }
        val error = request(HttpMethod.PUT, "/products/${p.id}/composition",
            input(listOf(component(a.id, "90", "PERCENT")), "PERCENT"), 422)
        assertThat(error["code"].asText()).isEqualTo("INVALID_COMPOSITION")
        assertThat(catalog.get(actor, p.id)).isEqualTo(before)
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.products WHERE owner_id=:owner")
            .param("owner", actor.id).query(Int::class.java).single()).isEqualTo(count)
    }

    @Test fun `diary materialization and history use current ingredient yield while consumed grams remain unchanged`() {
        val a = leaf(); val b = leaf("80")
        val p = create(input(listOf(component(a.id, "100", factor = "0.5"), component(b.id, "100", factor = "1.5"))))
        val entry = request(HttpMethod.POST, "/diary/entries", mapOf("productId" to p.id, "quantity" to "200",
            "unit" to "g", "occurredAt" to "2026-11-03T08:00:00Z", "mealLabelIds" to emptyList<String>()), 201)
        assertThat(entry["nutrition"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("480")
        val entryId = entry["id"].asText()
        fun day() = request(HttpMethod.GET, "/diary?date=2026-11-03&zone=Europe/Moscow")
        assertThat(day()["total"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("480")
        write(p.id, input(listOf(component(a.id, "100"), component(b.id, "100", present = true))))
        assertThat(day()["total"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("640")
        val patched = request(HttpMethod.PATCH, "/diary/entries/$entryId", mapOf("occurredAt" to "2026-11-03T09:00:00Z"))
        assertThat(patched["consumedGrams"].asText().toBigDecimal()).isEqualByComparingTo("200")
        assertThat(patched["nutrition"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("640")
        assertThat(jdbc.sql("SELECT consumed_grams FROM calorica.diary_entries WHERE id=:id")
            .param("id", UUID.fromString(entryId)).query(BigDecimal::class.java).single()).isEqualByComparingTo("200")
    }

    @Test fun `waiting GRAMS write resolves retained factors only after owner lock and rolls back conflicting output`() {
        val a = leaf(); val p = create(input(listOf(component(a.id, "1"))))
        val changed = CountDownLatch(1); val commit = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val changing = pool.submit { transactions.mutation { tx ->
                locks.personal(actor, tx)
                jdbc.sql("UPDATE calorica.product_components SET yield_factor=0.5 WHERE parent_id=:id")
                    .param("id", p.id).update()
                changed.countDown(); check(commit.await(5, TimeUnit.SECONDS)); Unit
            } }
            assertThat(changed.await(3, TimeUnit.SECONDS)).isTrue()
            val waiting = pool.submit<String> {
                try { write(p.id, input(listOf(component(a.id, "1")), "GRAMS", "outputGrams" to "0.8")); "OK" }
                catch (e: CatalogFailure) { e.code }
            }
            awaitBlocked(); commit.countDown(); changing.get(3, TimeUnit.SECONDS)
            assertThat(waiting.get(3, TimeUnit.SECONDS)).isEqualTo("YIELD_MODE_CONFLICT")
            assertThat(catalog.get(actor, p.id).effectiveYieldFactor).isEqualTo("0.5")
            assertThat(catalog.get(actor, p.id).manualYieldFactor).isNull()
        } finally { commit.countDown(); pool.shutdownNow() }
    }
    private fun awaitBlocked() {
        DriverManager.getConnection(requireNotNull(System.getenv("DB_URL")), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD")).use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = 1
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (System.nanoTime() < deadline) {
                    val waiting = statement.executeQuery("""SELECT count(*) FROM pg_locks WHERE locktype='advisory'
                        AND NOT granted AND classid=1128352848
                        AND database=(SELECT oid FROM pg_database WHERE datname=current_database())""").use { it.next(); it.getInt(1) }
                    if (waiting > 0) return
                    Thread.sleep(10)
                }
            }
        }
        fail<Unit>("Composition update never waited for owner lock")
    }
}
