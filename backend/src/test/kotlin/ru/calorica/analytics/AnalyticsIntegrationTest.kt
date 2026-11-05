package ru.calorica.analytics

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
import ru.calorica.analytics.application.AnalyticsService
import ru.calorica.analytics.domain.CalendarWindow
import ru.calorica.analytics.persistence.AnalyticsRepository
import ru.calorica.catalog.application.*
import ru.calorica.catalog.application.labels.ProductLabelRead
import ru.calorica.catalog.application.labels.ProductLabels
import ru.calorica.catalog.domain.*
import ru.calorica.diary.application.DiaryAnalyticsRead
import ru.calorica.diary.application.DiaryService
import ru.calorica.diary.domain.DiaryPatch
import ru.calorica.diary.domain.DiaryWrite
import ru.calorica.diary.persistence.DiaryRepository
import ru.calorica.platform.security.Actor
import ru.calorica.platform.security.Role
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsIntegrationTest @Autowired constructor(private val http: TestRestTemplate, private val mapper: ObjectMapper,
    private val jdbc: JdbcClient, private val accounts: AccountsService, private val catalog: CatalogService,
    private val diary: DiaryService, private val analytics: AnalyticsService, private val transactions: CatalogTransactions,
    private val diaryRepository: DiaryRepository, private val repository: AnalyticsRepository,
    private val productLabels: ProductLabelRead, private val labelService: ProductLabels) {
    data class User(val actor: Actor, val token: String)
    private lateinit var user: User
    private lateinit var meals: List<UUID>
    private fun register(role: Role = Role.USER): User {
        val tokens = accounts.register(Credentials("t15-${UUID.randomUUID()}@example.org", "analytics test password8"))
        if (role == Role.ADMIN) jdbc.sql("UPDATE calorica.accounts SET role='ADMIN' WHERE id=:id")
            .param("id", tokens.account.id).update()
        val session = jdbc.sql("SELECT id FROM calorica.sessions WHERE account_id=:id")
            .param("id", tokens.account.id).query(UUID::class.java).single()
        return User(Actor(tokens.account.id, role, session), tokens.accessToken)
    }
    @BeforeEach fun setup() { user = register(); meals = diary.labels().map { it.id } }
    private fun nutrition(calories: String) = Nutrition(BigDecimal(calories), BigDecimal("2"), BigDecimal("3"), BigDecimal("4"))
    private fun product(calories: String = "100") = catalog.create(user.actor, "Analytics-${UUID.randomUUID()}", nutrition(calories)).id
    private fun entry(product: UUID, time: String = "2026-05-06T12:00:00Z", grams: String = "100", labels: List<UUID> = emptyList()) =
        diary.create(user.actor, DiaryWrite(product, BigDecimal(grams), "g", Instant.parse(time), labels))
    private fun summary(date: String = "2026-05-06", zone: String = "UTC", period: String = "DAY") =
        analytics.summary(user.actor, date, zone, period)
    private fun request(query: String, current: User? = user): ResponseEntity<String> {
        val headers = HttpHeaders().apply { current?.let { setBearerAuth(it.token) } }
        return http.exchange("/api/v1/analytics?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }
    private fun json(response: ResponseEntity<String>): JsonNode = mapper.readTree(response.body)
    private fun api(current: User, method: HttpMethod, path: String, body: Any? = null, status: Int = 200): JsonNode {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON; setBearerAuth(current.token) }
        val response = http.exchange("/api/v1$path", method,
            HttpEntity(body?.let(mapper::writeValueAsString), headers), String::class.java)
        assertThat(response.statusCode.value()).describedAs("%s %s: %s", method, path, response.body).isEqualTo(status)
        return if (status == 204) mapper.nullNode() else json(response)
    }
    private fun label(admin: User): UUID = UUID.fromString(api(admin, HttpMethod.POST, "/admin/product-labels",
        mapOf("name" to "Analytics group-${UUID.randomUUID()}"), 201)["id"].asText())
    private fun assign(product: UUID, vararg labels: UUID) {
        api(user, HttpMethod.PUT, "/products/$product/labels", mapOf("labelIds" to labels.toList()))
    }
    private fun groups(response: JsonNode): Map<UUID, BigDecimal> = response["productLabels"].associate {
        assertThat(it["grams"].isTextual).isTrue()
        UUID.fromString(it["labelId"].asText()) to it["grams"].asText().toBigDecimal()
    }
    private fun problem(response: ResponseEntity<String>, status: Int, code: String) {
        assertThat(response.statusCode.value()).isEqualTo(status)
        assertThat(json(response)["code"].asText()).isEqualTo(code)
        assertThat(json(response)["correlationId"].asText()).isEqualTo(response.headers.getFirst("X-Request-Id"))
        assertThat(response.body).doesNotContain("SELECT ", "stackTrace", "password_hash")
    }
    @Test fun `HTTP full week includes empty dates and 700 calories means 100 per calendar day`() {
        entry(product("700"))
        val response = request("date=2026-05-06&zone=UTC&period=WEEK")
        assertThat(response.statusCode.value()).isEqualTo(200)
        val body = json(response)
        assertThat(body.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "startDate", "endDateExclusive", "zone", "dayCount", "days", "total", "dailyAverage", "mealLabels", "productLabels")
        assertThat(body["startDate"].asText()).isEqualTo("2026-05-04")
        assertThat(body["endDateExclusive"].asText()).isEqualTo("2026-05-11")
        assertThat(body["dayCount"].asInt()).isEqualTo(7)
        assertThat(body["days"].map { it["date"].asText() }).containsExactly(
            "2026-05-04", "2026-05-05", "2026-05-06", "2026-05-07", "2026-05-08", "2026-05-09", "2026-05-10")
        assertThat(body["total"]["calories"].isTextual).isTrue()
        assertThat(body["total"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("700")
        assertThat(body["dailyAverage"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("100")
        assertThat(body["days"].count { it["total"]["calories"].asText().toBigDecimal().signum() == 0 }).isEqualTo(6)
        assertThat(summary().total).isEqualTo(diary.day(user.actor, "2026-05-06", "UTC").total)
    }
    @Test fun `overlapping meal groups each receive full nutrition without multiplying total`() {
        entry(product(), labels = meals.take(2))
        val result = summary()
        assertThat(result.total.calories).isEqualByComparingTo("100")
        assertThat(result.dailyAverage.calories).isEqualByComparingTo("100")
        assertThat(result.mealLabels.map { it.labelId }).containsExactlyElementsOf(meals.take(2).sortedBy { it.toString() })
        assertThat(result.mealLabels.map { it.total.calories }).allSatisfy { assertThat(it).isEqualByComparingTo("100") }
        assertThat(result.mealLabels.map { it.total.protein }).allSatisfy { assertThat(it).isEqualByComparingTo("2") }
    }
    @Test fun `whole months and empty periods keep all dates and zero totals`() {
        entry(product("290"), "2028-02-29T23:59:59Z")
        entry(product("999"), "2028-03-01T00:00:00Z")
        val leap = summary("2028-02-10", period = "MONTH")
        assertThat(leap.dayCount).isEqualTo(29)
        assertThat(leap.days).hasSize(29)
        assertThat(leap.total.calories).isEqualByComparingTo("290")
        assertThat(leap.dailyAverage.calories).isEqualByComparingTo("10")
        for (period in listOf("DAY", "WEEK", "MONTH")) {
            val empty = summary("2026-12-12", "Pacific/Apia", period)
            assertThat(empty.days).hasSize(empty.dayCount)
            assertThat(empty.days.map { it.total.calories }).allSatisfy { assertThat(it).isEqualByComparingTo("0") }
            assertThat(empty.total.calories).isEqualByComparingTo("0")
            assertThat(empty.dailyAverage.calories).isEqualByComparingTo("0")
            assertThat(empty.mealLabels).isEmpty()
            assertThat(empty.productLabels).isEmpty()
        }
        assertThat(summary("2011-12-30", "Pacific/Apia").days).hasSize(1)
    }
    @Test fun `half open boundaries and local grouping follow both DST transitions and timezone changes`() {
        val product = product()
        for (date in listOf("2026-03-29", "2026-10-25")) {
            val window = CalendarWindow.select(date, "Europe/Berlin", "DAY")
            for (instant in listOf(window.start.minusSeconds(1), window.start, window.end.minusSeconds(1), window.end))
                entry(product, instant.toString())
            val day = summary(date, "Europe/Berlin")
            assertThat(day.total.calories).isEqualByComparingTo("200")
            assertThat(day.dailyAverage.calories).isEqualByComparingTo("200")
            assertThat(day.days.single().total.calories).isEqualByComparingTo("200")
            assertThat(summary(date, "UTC").total.calories).isEqualByComparingTo("200")
            val week = summary(date, "Europe/Berlin", "WEEK")
            assertThat(week.days.single { it.date.toString() == date }.total.calories).isEqualByComparingTo("200")
        }
        entry(product, "2026-07-01T22:30:00Z")
        assertThat(summary("2026-07-02", "Europe/Moscow").total.calories).isEqualByComparingTo("100")
        assertThat(summary("2026-07-02", "UTC").total.calories).isEqualByComparingTo("0")
    }
    private fun composition(children: List<Pair<UUID, String>>, yield: String? = null) = CompositionWrite(
        children.map { ComponentWrite(it.first, BigDecimal(it.second), OptionalFactor()) },
        OptionalFactor(true, yield?.toBigDecimal()))
    @Test fun `shared graph paths recalculate after leaf and yield edits and survive soft deletion`() {
        val leaf = product()
        val child = catalog.createComposition(user.actor, "Child", composition(listOf(leaf to "1"))).id
        val root = catalog.createComposition(user.actor, "Root", composition(listOf(child to "0.5", leaf to "0.5"), "0.5")).id
        entry(root, grams = "200")
        assertThat(summary().total.calories).isEqualByComparingTo("400")
        catalog.patch(user.actor, leaf, null, nutrition("200"))
        assertThat(summary().total.calories).isEqualByComparingTo("800")
        catalog.writeComposition(user.actor, root, composition(listOf(child to "0.5", leaf to "0.5"), "0.25"))
        assertThat(summary().total.calories).isEqualByComparingTo("1600")
        catalog.delete(user.actor, leaf, "KEEP_REFERENCES")
        catalog.delete(user.actor, root, "KEEP_REFERENCES")
        assertThat(summary().total.calories).isEqualByComparingTo("1600")
    }
    @Test fun `HTTP authentication ownership and input validation preserve safe problem contract`() {
        entry(product())
        val other = register()
        val query = "date=2026-05-06&zone=UTC&period=DAY"
        problem(request(query, null), 401, "UNAUTHORIZED")
        assertThat(json(request(query, other))["total"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("0")
        problem(request("date=2026-05-06&zone=UTC"), 400, "BAD_REQUEST")
        problem(request("date=2026-02-30&zone=UTC&period=DAY"), 422, "VALIDATION_FAILED")
        problem(request("date=2026-05-06&zone=Unknown/Zone&period=DAY"), 422, "VALIDATION_FAILED")
        problem(request("date=2026-05-06&zone=UTC&period=YEAR"), 422, "VALIDATION_FAILED")
    }
    @Test fun `whole response retains one snapshot when concurrent writer edits graph and diary labels`() {
        val product = product()
        val original = entry(product, labels = meals.take(1))
        val admin = register(Role.ADMIN)
        val oldLabel = label(admin); val newLabel = label(admin)
        assign(product, oldLabel)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val interleavingCalculation = object : CatalogCalculation by catalog {
                override fun nutrition(actor: Actor, productIds: Collection<UUID>, snapshot: CatalogSnapshot): Map<UUID, CalculatedNutrition> {
                    val old = catalog.nutrition(actor, productIds, snapshot)
                    executor.submit(Callable {
                        catalog.patch(user.actor, product, null, nutrition("200"))
                        diary.patch(user.actor, original.id, DiaryPatch(quantity = BigDecimal("200"), unit = "g", mealLabelIds = meals.drop(1).take(1)))
                        labelService.assign(user.actor, product, listOf(newLabel))
                        labelService.delete(admin.actor, oldLabel)
                    }).get(4, TimeUnit.SECONDS)
                    return old
                }
            }
            val interleaved = AnalyticsService(DiaryAnalyticsRead(diaryRepository, interleavingCalculation, transactions),
                repository, productLabels, transactions).summary(user.actor, "2026-05-06", "UTC", "DAY")
            assertThat(interleaved.total.calories).isEqualByComparingTo("100")
            assertThat(interleaved.days.single().total.calories).isEqualByComparingTo("100")
            assertThat(interleaved.mealLabels.single().labelId).isEqualTo(meals.first())
            assertThat(interleaved.mealLabels.single().total.calories).isEqualByComparingTo("100")
            assertThat(interleaved.productLabels.single().labelId).isEqualTo(oldLabel)
            assertThat(interleaved.productLabels.single().grams).isEqualByComparingTo("100")
            val next = summary()
            assertThat(next.total.calories).isEqualByComparingTo("400")
            assertThat(next.mealLabels.single().labelId).isEqualTo(meals[1])
            assertThat(next.productLabels.single().labelId).isEqualTo(newLabel)
            assertThat(next.productLabels.single().grams).isEqualByComparingTo("200")
        } finally { executor.shutdownNow() }
    }
    @Test fun `period guard rejects 10001 rows across days without a truncated summary`() {
        val product = product()
        jdbc.sql("""INSERT INTO calorica.diary_entries(id,owner_id,product_id,consumed_grams,occurred_at)
            SELECT gen_random_uuid(),:owner,:product,1,
                timestamptz '2031-01-01 12:00:00Z' + (n % 28) * interval '1 day'
            FROM generate_series(1,10001) AS n""")
            .param("owner", user.actor.id).param("product", product).update()
        problem(request("date=2031-01-12&zone=UTC&period=MONTH"), 422, "CALCULATION_LIMIT")
        assertThat(summary("2031-01-12").total.calories.signum()).isPositive()
    }
    @Test fun `aggregation retains sub display precision until the final response`() {
        val product = product("0.01")
        repeat(7) { entry(product, grams = "1", labels = meals.take(2)) }
        val week = summary(period = "WEEK")
        assertThat(week.total.calories).isEqualByComparingTo("0.0007")
        assertThat(week.dailyAverage.calories).isEqualByComparingTo("0.0001")
        assertThat(week.mealLabels.map { it.total.calories }).allSatisfy { assertThat(it).isEqualByComparingTo("0.0007") }
    }
    @Test fun `HTTP product groups allocate ingredient yield and repeat overlaps without inflating nutrition`() {
        val admin = register(Role.ADMIN)
        val shared = label(admin); val aGroup = label(admin); val bGroup = label(admin); val parentGroup = label(admin)
        val a = product("400"); val b = product("80"); val unlabelled = product("50")
        assign(a, shared, aGroup); assign(b, shared, bGroup)
        val recipe = catalog.createComposition(user.actor, "Ingredient yield", CompositionWrite(listOf(
            ComponentWrite(a, BigDecimal("0.5"), OptionalFactor(true, BigDecimal("0.5"))),
            ComponentWrite(b, BigDecimal("0.5"), OptionalFactor(true, BigDecimal("1.5")))), OptionalFactor())).id
        assign(recipe, parentGroup)
        entry(recipe, grams = "200", labels = meals.take(2))
        entry(recipe, grams = "100", labels = meals.take(2))
        entry(unlabelled)
        for (period in listOf("DAY", "WEEK", "MONTH")) {
            val response = request("date=2026-05-06&zone=UTC&period=$period")
            assertThat(response.statusCode.value()).isEqualTo(200)
            val body = json(response)
            val actual = groups(body)
            assertThat(actual.keys).containsExactlyElementsOf(listOf(shared, aGroup, bGroup).sortedBy { it.toString() })
            assertThat(actual.getValue(shared)).isEqualByComparingTo("300")
            assertThat(actual.getValue(aGroup)).isEqualByComparingTo("75")
            assertThat(actual.getValue(bGroup)).isEqualByComparingTo("225")
            assertThat(actual).doesNotContainKey(parentGroup)
            assertThat(body["total"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("770")
            assertThat(body["mealLabels"].map { it["total"]["calories"].asText().toBigDecimal() })
                .allSatisfy { assertThat(it).isEqualByComparingTo("720") }
        }
    }
    @Test fun `manual yield preserves ready grams and product groups follow shared paths after cascade deletion`() {
        val admin = register(Role.ADMIN)
        val aGroup = label(admin); val bGroup = label(admin)
        val a = product("400"); val b = product("80")
        assign(a, aGroup); assign(b, bGroup)
        val child = catalog.createComposition(user.actor, "Shared child", composition(listOf(a to "1"))).id
        val root = catalog.createComposition(user.actor, "Manual yield", composition(
            listOf(child to "0.3", a to "0.3", b to "0.4"), "0.8")).id
        entry(root, grams = "200")
        fun verify(calories: String) {
            val response = request("date=2026-05-06&zone=UTC&period=DAY")
            assertThat(response.statusCode.value()).isEqualTo(200)
            val body = json(response)
            assertThat(body["total"]["calories"].asText().toBigDecimal()).isEqualByComparingTo(calories)
            val actual = groups(body)
            assertThat(actual.getValue(aGroup)).isEqualByComparingTo("120")
            assertThat(actual.getValue(bGroup)).isEqualByComparingTo("80")
        }
        verify("680")
        catalog.writeComposition(user.actor, root, composition(listOf(child to "0.3", a to "0.3", b to "0.4"), "0.4"))
        verify("1360")
        catalog.delete(user.actor, a, "CASCADE")
        assertThat(catalog.get(user.actor, root).isDeleted).isTrue()
        verify("1360")
    }
    @Test fun `product labels track type transitions reassignment and deletion retroactively through HTTP`() {
        val admin = register(Role.ADMIN)
        val parentGroup = label(admin); val childGroup = label(admin); val replacement = label(admin)
        val parent = product(); val child = product("200")
        assign(parent, parentGroup); assign(child, childGroup)
        entry(parent)
        fun currentGroups() = groups(json(request("date=2026-05-06&zone=UTC&period=DAY")))
        assertThat(currentGroups().keys).containsExactly(parentGroup)
        api(user, HttpMethod.PUT, "/products/$parent/composition", mapOf("inputMode" to "PERCENT",
            "components" to listOf(mapOf("productId" to child, "percent" to "100"))))
        assertThat(currentGroups().keys).containsExactly(childGroup)
        assertThat(summary().total.calories).isEqualByComparingTo("200")
        assign(child, replacement)
        assertThat(currentGroups().keys).containsExactly(replacement)
        api(admin, HttpMethod.PATCH, "/admin/product-labels/$replacement", mapOf("name" to "Renamed"))
        assertThat(currentGroups().getValue(replacement)).isEqualByComparingTo("100")
        api(admin, HttpMethod.DELETE, "/admin/product-labels/$replacement", status = 204)
        assertThat(currentGroups()).isEmpty()
        assertThat(summary().total.calories).isEqualByComparingTo("200")
        api(user, HttpMethod.PUT, "/products/$parent/composition", mapOf("inputMode" to "PERCENT", "components" to emptyList<Any>()))
        assertThat(currentGroups().keys).containsExactly(parentGroup)
        assertThat(summary().total.calories).isEqualByComparingTo("100")
    }

}
