package ru.calorica.analytics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.*
import org.springframework.jdbc.core.simple.JdbcClient
import ru.calorica.accounts.application.AccountsService
import ru.calorica.accounts.domain.Credentials
import ru.calorica.analytics.application.AnalyticsService
import ru.calorica.analytics.domain.CalendarWindow
import ru.calorica.analytics.persistence.AnalyticsRepository
import ru.calorica.catalog.application.*
import ru.calorica.catalog.application.labels.ProductLabelRead
import ru.calorica.catalog.domain.*
import ru.calorica.catalog.domain.labels.ProductLabelGrams
import ru.calorica.diary.application.DiaryAnalyticsRead
import ru.calorica.diary.application.DiaryService
import ru.calorica.diary.domain.DiaryPatch
import ru.calorica.diary.domain.DiaryWrite
import ru.calorica.diary.persistence.DiaryRepository
import ru.calorica.platform.security.Actor
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Temporary test-only port until T13 is integrated. These tests certify calendar/meal behavior only. */
@TestConfiguration(proxyBeanMethods = false)
class PendingProductLabels {
    @Bean fun productLabelRead(): ProductLabelRead = object : ProductLabelRead {
        override fun distribution(actor: Actor, portions: List<CatalogPortion>, snapshot: CatalogSnapshot): List<ProductLabelGrams> = emptyList()
    }
}

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PendingProductLabels::class)
class AnalyticsIntegrationTest @Autowired constructor(private val http: TestRestTemplate, private val mapper: ObjectMapper,
    private val jdbc: JdbcClient, private val accounts: AccountsService, private val catalog: CatalogService,
    private val diary: DiaryService, private val analytics: AnalyticsService, private val transactions: CatalogTransactions,
    private val diaryRepository: DiaryRepository, private val repository: AnalyticsRepository,
    private val productLabels: ProductLabelRead) {
    data class User(val actor: Actor, val token: String)
    private lateinit var user: User
    private lateinit var meals: List<UUID>
    private fun register(): User {
        val tokens = accounts.register(Credentials("t15-${UUID.randomUUID()}@example.org", "analytics test password8"))
        val session = jdbc.sql("SELECT id FROM calorica.sessions WHERE account_id=:id")
            .param("id", tokens.account.id).query(UUID::class.java).single()
        return User(Actor(tokens.account.id, tokens.account.role, session), tokens.accessToken)
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
        val executor = Executors.newSingleThreadExecutor()
        try {
            val interleavingCalculation = object : CatalogCalculation by catalog {
                override fun nutrition(actor: Actor, productIds: Collection<UUID>, snapshot: CatalogSnapshot): Map<UUID, CalculatedNutrition> {
                    val old = catalog.nutrition(actor, productIds, snapshot)
                    executor.submit(Callable {
                        catalog.patch(user.actor, product, null, nutrition("200"))
                        diary.patch(user.actor, original.id, DiaryPatch(quantity = BigDecimal("200"), unit = "g", mealLabelIds = meals.drop(1).take(1)))
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
            val next = summary()
            assertThat(next.total.calories).isEqualByComparingTo("400")
            assertThat(next.mealLabels.single().labelId).isEqualTo(meals[1])
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
}
