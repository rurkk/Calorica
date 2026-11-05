package ru.calorica.diary

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
import ru.calorica.catalog.application.*
import ru.calorica.catalog.application.units.CatalogUnits
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.catalog.persistence.CatalogRepository
import ru.calorica.diary.application.DiaryService
import ru.calorica.diary.domain.*
import ru.calorica.diary.persistence.DiaryRepository
import ru.calorica.diary.persistence.DiaryRow
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.security.Actor
import ru.calorica.platform.security.Role
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiaryIntegrationTest @Autowired constructor(private val http: TestRestTemplate, private val mapper: ObjectMapper,
    private val jdbc: JdbcClient, private val accounts: AccountsService, private val catalog: CatalogService,
    private val diary: DiaryService, private val transactions: CatalogTransactions, private val locks: CatalogMutationLock,
    private val catalogRepository: CatalogRepository, private val units: CatalogUnits,
    private val diaryRepository: DiaryRepository) {
    data class User(val actor: Actor, val token: String)
    private lateinit var a: User
    private lateinit var b: User
    private lateinit var admin: User
    private lateinit var labels: List<UUID>
    private val at = Instant.parse("2026-05-01T10:00:00Z")
    @BeforeAll fun setup() {
        fun user(role: Role = Role.USER): User {
            val tokens = accounts.register(Credentials("t07-${UUID.randomUUID()}@example.org", "diary test password8"))
            if (role == Role.ADMIN) jdbc.sql("UPDATE calorica.accounts SET role='ADMIN' WHERE id=:id").param("id", tokens.account.id).update()
            val session = jdbc.sql("SELECT id FROM calorica.sessions WHERE account_id=:id").param("id", tokens.account.id).query(UUID::class.java).single()
            return User(Actor(tokens.account.id, role, session), tokens.accessToken)
        }
        a = user(); b = user(); admin = user(Role.ADMIN)
        labels = diary.labels().map { it.id }
        assertThat(labels).hasSize(4)
    }
    private fun request(user: User?, method: HttpMethod, path: String, body: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON; user?.let { setBearerAuth(it.token) } }
        return http.exchange("/api/v1$path", method, HttpEntity(body, headers), String::class.java)
    }
    private fun json(r: ResponseEntity<String>): JsonNode = mapper.readTree(r.body)
    private fun problem(r: ResponseEntity<String>, status: Int, code: String) {
        assertThat(r.statusCode.value()).isEqualTo(status)
        assertThat(json(r)["code"].asText()).isEqualTo(code)
        assertThat(json(r)["correlationId"].asText()).isEqualTo(r.headers.getFirst("X-Request-Id"))
        assertThat(r.body).doesNotContain("SELECT ","stackTrace","password_hash","diary test password8")
    }
    private fun product(user: User = a, c: String = "123.45", base: Boolean = false): UUID =
        catalog.create(user.actor, "Дневник-${UUID.randomUUID()}", Nutrition(BigDecimal(c),BigDecimal("1.23"),BigDecimal("2.34"),BigDecimal("3.45")),base).id
    private fun write(product: UUID, grams: String = "100", time: Instant = at, tags: List<UUID> = emptyList(), unit: String = "g") =
        DiaryWrite(product,BigDecimal(grams),unit,time,tags)
    private fun body(product: UUID, q: String = "100", tags: List<UUID> = emptyList(), unit: String = "g") =
        mapper.writeValueAsString(mapOf("productId" to product,"quantity" to q,"unit" to unit,"occurredAt" to at.toString(),"mealLabelIds" to tags))

    @Test fun `HTTP diary CRUD returns exact decimal strings and editing does not mutate product`() {
        problem(request(null,HttpMethod.GET,"/diary?date=2026-05-01&zone=UTC"),401,"UNAUTHORIZED")
        assertThat(request(a,HttpMethod.GET,"/meal-labels").statusCode.value()).isEqualTo(200)
        val product = product()
        val created = request(a,HttpMethod.POST,"/diary/entries",body(product,"12.345",labels.take(2)))
        assertThat(created.statusCode.value()).isEqualTo(201)
        val id = json(created)["id"].asText()
        assertThat(json(created)["consumedGrams"].isTextual).isTrue()
        assertThat(json(created)["nutrition"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("15.2399025")
        assertThat(json(created)["mealLabelIds"]).hasSize(2)
        val patched = request(a,HttpMethod.PATCH,"/diary/entries/$id","""{"quantity":"125","unit":"g","mealLabelIds":[]}""")
        assertThat(patched.statusCode.value()).isEqualTo(200)
        assertThat(json(patched)["nutrition"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("154.3125")
        assertThat(json(patched)["mealLabelIds"]).isEmpty()
        assertThat(json(request(a,HttpMethod.PATCH,"/diary/entries/$id","{}"))).isEqualTo(json(patched))
        assertThat(request(a,HttpMethod.DELETE,"/diary/entries/$id").statusCode.value()).isEqualTo(204)
        problem(request(a,HttpMethod.GET,"/diary/entries/$id"),404,"NOT_FOUND")
        assertThat(catalog.get(a.actor,product).isDeleted).isFalse()
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.diary_entry_labels WHERE entry_id=CAST(:id AS uuid)").param("id",id).query(Int::class.java).single()).isZero()
    }
    private fun largeProduct(): UUID {
        var id = product(c="1")
        repeat(8) {
            val created = request(a,HttpMethod.POST,"/products",mapper.writeValueAsString(mapOf(
                "name" to "Atomic-${UUID.randomUUID()}",
                "composition" to mapOf("inputMode" to "PERCENT", "manualYieldFactor" to "0.000000000001",
                    "components" to listOf(mapOf("productId" to id, "percent" to "100"))))))
            assertThat(created.statusCode.value()).isEqualTo(201)
            id = UUID.fromString(json(created)["id"].asText())
        }
        assertThat(catalog.get(a.actor,id).nutritionPer100g.calories).isEqualByComparingTo("1e96")
        return id
    }
    @Test fun `HTTP create rolls back entry and labels when its portion exceeds calculation limits`() {
        val product = largeProduct()
        val before = diary.day(a.actor,"2026-05-01","UTC")
        try {
            problem(request(a,HttpMethod.POST,"/diary/entries",body(product,"1000000",labels.take(2))),422,"CALCULATION_LIMIT")
            assertThat(jdbc.sql("SELECT count(*) FROM calorica.diary_entries WHERE product_id=:product")
                .param("product",product).query(Int::class.java).single()).isZero()
            assertThat(jdbc.sql("""SELECT count(*) FROM calorica.diary_entry_labels l
                JOIN calorica.diary_entries e ON e.id=l.entry_id WHERE e.product_id=:product""")
                .param("product",product).query(Int::class.java).single()).isZero()
            assertThat(diary.day(a.actor,"2026-05-01","UTC")).isEqualTo(before)
        } finally {
            jdbc.sql("DELETE FROM calorica.diary_entries WHERE product_id=:product").param("product",product).update()
        }
    }
    @Test fun `HTTP patch rolls back product quantity time and labels when its portion exceeds calculation limits`() {
        val product = largeProduct()
        val entry = diary.create(a.actor,write(product(),tags=labels.take(1)))
        val before = diary.get(a.actor,entry.id)
        val patch = mapper.writeValueAsString(mapOf("productId" to product, "quantity" to "1000000", "unit" to "g",
            "occurredAt" to at.plusSeconds(3600).toString(), "mealLabelIds" to labels.drop(1).take(2)))
        try {
            problem(request(a,HttpMethod.PATCH,"/diary/entries/${entry.id}",patch),422,"CALCULATION_LIMIT")
            assertThat(diary.get(a.actor,entry.id)).isEqualTo(before)
            val persisted = diaryRepository.get(a.actor.id,entry.id)!!
            assertThat(persisted.productId).isEqualTo(before.productId)
            assertThat(persisted.consumedGrams).isEqualByComparingTo(before.consumedGrams)
            assertThat(persisted.occurredAt).isEqualTo(before.occurredAt)
            assertThat(persisted.mealLabelIds).containsExactlyElementsOf(before.mealLabelIds)
            assertThat(request(a,HttpMethod.GET,"/diary?date=2026-05-01&zone=UTC").statusCode.value()).isEqualTo(200)
        } finally {
            jdbc.sql("DELETE FROM calorica.diary_entries WHERE id=:id").param("id",entry.id).update()
        }
    }
    @Test fun `ownership comes from session and foreign entries cannot be read changed or deleted`() {
        val entry = diary.create(a.actor,write(product()))
        for (method in listOf(HttpMethod.GET,HttpMethod.PATCH,HttpMethod.DELETE))
            problem(request(b,method,"/diary/entries/${entry.id}",if(method==HttpMethod.PATCH) "{}" else null),404,"NOT_FOUND")
        val ownedBody = body(entry.productId).dropLast(1)+",\"ownerId\":\"${b.actor.id}\"}"
        problem(request(a,HttpMethod.POST,"/diary/entries",ownedBody),400,"BAD_REQUEST")
        problem(request(b,HttpMethod.POST,"/diary/entries",body(entry.productId)),404,"NOT_FOUND")
        assertThat(diary.day(b.actor,"2026-05-01","UTC").entries).isEmpty()
        assertThat(diary.get(a.actor,entry.id).id).isEqualTo(entry.id)
    }
    @Test fun `retained deleted product permits editing old portion but rejects new references`() {
        val product = product()
        val entry = diary.create(a.actor,write(product))
        catalog.delete(a.actor,product,"KEEP_REFERENCES")
        assertThat(diary.get(a.actor,entry.id).productDeleted).isTrue()
        val changed = diary.patch(a.actor,entry.id,DiaryPatch(quantity=BigDecimal("200"),unit="g",occurredAt=at.plusSeconds(60)))
        assertThat(changed.consumedGrams).isEqualByComparingTo("200")
        assertThatThrownBy { diary.create(a.actor,write(product)) }.isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(404) }
        val other = diary.create(a.actor,write(product()))
        assertThatThrownBy { diary.patch(a.actor,other.id,DiaryPatch(productId=product)) }.isInstanceOf(CatalogFailure::class.java)
        assertThat(diary.get(a.actor,other.id).productId).isEqualTo(other.productId)
    }
    @Test fun `day totals are retrospective sum before rounding and multiple labels never duplicate portions`() {
        val product = product(c="0.01")
        val time = Instant.parse("2026-07-13T10:00:00Z")
        repeat(2) { diary.create(a.actor,write(product,"1",time,labels.take(2))) }
        assertThat(diary.day(a.actor,"2026-07-13","UTC").total.calories).isEqualByComparingTo("0.0002")
        catalog.patch(a.actor,product,null,Nutrition(BigDecimal("0.02"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO))
        val day = diary.day(a.actor,"2026-07-13","UTC")
        assertThat(day.total.calories).isEqualByComparingTo("0.0004")
        assertThat(day.entries).hasSize(2)
        assertThat(day.entries.map { it.nutrition.calories }).allSatisfy { assertThat(it).isEqualByComparingTo("0.0002") }
        val empty = diary.day(a.actor,"2026-07-14","UTC")
        assertThat(empty.entries).isEmpty(); assertThat(empty.total.calories).isEqualByComparingTo("0")
    }
    @Test fun `day uses half open UTC boundaries and DST day lengths with deterministic ordering`() {
        val product = product()
        val start = Instant.parse("2026-03-28T23:00:00Z")
        val end = Instant.parse("2026-03-29T22:00:00Z")
        val before = diary.create(a.actor,write(product,time=start.minusSeconds(1)))
        val first = diary.create(a.actor,write(product,time=start))
        val same = diary.create(a.actor,write(product,time=start))
        val last = diary.create(a.actor,write(product,time=end.minusSeconds(1)))
        val after = diary.create(a.actor,write(product,time=end))
        val day = diary.day(a.actor,"2026-03-29","Europe/Berlin")
        assertThat(day.entries.map { it.id }).containsExactlyElementsOf(listOf(first.id,same.id).sortedBy { it.toString() }+last.id)
        assertThat(day.entries.map { it.id }).doesNotContain(before.id,after.id)
        assertThat(diary.day(a.actor,"2026-03-28","UTC").entries.map { it.id }).contains(before.id,first.id,same.id)
        problem(request(a,HttpMethod.GET,"/diary?date=2026-02-30&zone=UTC"),422,"VALIDATION_FAILED")
        problem(request(a,HttpMethod.GET,"/diary?date=2026-01-01&zone=Fake/Zone"),422,"VALIDATION_FAILED")
        problem(request(a,HttpMethod.GET,"/diary?date=2026-01-01"),400,"BAD_REQUEST")
    }
    @Test fun `real diary API converts ml stores only grams and rejects absent conversion atomically`() {
        val base = product(admin,base=true)
        units.setMillilitres(admin.actor,base,BigDecimal("1.03"))
        val response = request(a,HttpMethod.POST,"/diary/entries",body(base,"200",unit="ml"))
        assertThat(response.statusCode.value()).isEqualTo(201)
        val id = UUID.fromString(json(response)["id"].asText())
        assertThat(diary.get(a.actor,id).consumedGrams).isEqualByComparingTo("206")
        assertThat(jdbc.sql("SELECT consumed_grams FROM calorica.diary_entries WHERE id=:id").param("id",id).query(BigDecimal::class.java).single()).isEqualByComparingTo("206")
        val columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='calorica' AND table_name='diary_entries'").query(String::class.java).list()
        assertThat(columns).doesNotContain("quantity","unit","calories","protein","fat","carbs","nutrition")
        val personal = product()
        problem(request(a,HttpMethod.POST,"/diary/entries",body(personal,"200",unit="ml")),422,"UNIT_UNAVAILABLE")
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.diary_entries WHERE product_id=:product").param("product",personal).query(Int::class.java).single()).isZero()
    }
    @Test fun `invalid body labels and paired patch fields do not change persisted data`() {
        val entry = diary.create(a.actor,write(product()))
        for (body in listOf("{\"quantity\":\"2\"}","{\"unit\":\"g\"}","{\"occurredAt\":null}","{\"mealLabelIds\":[\"${UUID.randomUUID()}\"]}"))
            problem(request(a,HttpMethod.PATCH,"/diary/entries/${entry.id}",body),422,"VALIDATION_FAILED")
        assertThat(diary.get(a.actor,entry.id)).isEqualTo(entry)
        problem(request(a,HttpMethod.PATCH,"/diary/entries/${entry.id}","{\"quantity\":2,\"unit\":\"g\"}"),400,"BAD_REQUEST")
    }
    @Test fun `new entry waiting on product deletion rechecks committed availability and rolls back`() {
        val product = product()
        val deleted = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val deleting = executor.submit(Callable { transactions.mutation { tx ->
                locks.personal(a.actor,tx); catalogRepository.softDelete(product,a.actor.id)
                deleted.countDown(); check(release.await(5,TimeUnit.SECONDS)); Unit
            } })
            assertThat(deleted.await(3,TimeUnit.SECONDS)).isTrue()
            val creating = executor.submit(Callable { runCatching { diary.create(a.actor,write(product)) }.exceptionOrNull() })
            awaitBlocked(); release.countDown(); deleting.get(3,TimeUnit.SECONDS)
            assertThat(creating.get(3,TimeUnit.SECONDS)).isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(404) }
            assertThat(jdbc.sql("SELECT count(*) FROM calorica.diary_entries WHERE product_id=:id").param("id",product).query(Int::class.java).single()).isZero()
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun `calculation failure inside mutation rolls back entry and label changes`() {
        val product = product()
        val failingCalculation = object : CatalogCalculation by catalog {
            override fun nutrition(actor: Actor, productIds: Collection<UUID>, tx: CatalogWriteTransaction): Map<UUID,CalculatedNutrition> =
                throw CatalogFailure(422,"CALCULATION_LIMIT")
        }
        val failingRead = DiaryService(diaryRepository,catalog,failingCalculation,transactions,locks,units)
        assertThatThrownBy { failingRead.create(a.actor,write(product,"10",tags=labels.take(2))) }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(422); assertThat(it.code).isEqualTo("CALCULATION_LIMIT") }
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.diary_entries WHERE product_id=:id")
            .param("id",product).query(Int::class.java).single()).isZero()
        val entry = diary.create(a.actor,write(product,"10",tags=labels.take(1)))
        assertThatThrownBy { failingRead.patch(a.actor,entry.id,
            DiaryPatch(quantity=BigDecimal("20"),unit="g",mealLabelIds=labels.drop(1).take(2))) }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(422) }
        assertThat(diary.get(a.actor,entry.id)).isEqualTo(entry)
    }
    @Test fun `catalog mutation reads require live capabilities and matching owner locks`() {
        val product = product()
        fun rejected(tx: CatalogWriteTransaction, actor: Actor = a.actor) {
            assertThatThrownBy { catalog.getVisible(actor,listOf(product),true,tx) }
                .isInstanceOf(IllegalStateException::class.java)
            assertThatThrownBy { catalog.nutrition(actor,listOf(product),tx) }
                .isInstanceOf(IllegalStateException::class.java)
        }
        val stale = transactions.mutation { tx ->
            rejected(tx)
            locks.personal(a.actor,tx)
            rejected(tx,b.actor)
            assertThat(catalog.getVisible(a.actor,listOf(product),true,tx).single().id).isEqualTo(product)
            assertThat(catalog.nutrition(a.actor,listOf(product),tx)).containsKey(product)
            tx
        }
        rejected(stale)
        transactions.mutation { current ->
            locks.personal(a.actor,current)
            rejected(stale)
            Unit
        }
    }
    @Test fun `calculation guards cover both scaled portions and the final unrounded day sum`() {
        val product = product()
        val huge = Nutrition(BigDecimal("9".repeat(100)),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)
        val calculated = mapOf(product to CalculatedNutrition(huge,BigDecimal.ONE,"DEFAULT"))
        for(rows in listOf(
            listOf(DiaryRow(UUID.randomUUID(),product,BigDecimal("1000"),at,emptyList())),
            List(2) { DiaryRow(UUID.randomUUID(),product,BigDecimal("100"),at,emptyList()) }
        )) assertThatThrownBy { transactions.snapshot { diaryRepository.nutrition(rows,calculated) } }
            .isInstanceOfSatisfying(ApiFailure::class.java) { assertThat(it.status).isEqualTo(422); assertThat(it.code).isEqualTo("CALCULATION_LIMIT") }
    }
    @Test fun `oversized diary rejects the whole day instead of returning a truncated total`() {
        val product = product()
        jdbc.sql("""INSERT INTO calorica.diary_entries(id,owner_id,product_id,consumed_grams,occurred_at)
            SELECT gen_random_uuid(),:owner,:product,1,timestamptz '2030-01-01 12:00:00Z' FROM generate_series(1,10001)""")
            .param("owner",a.actor.id).param("product",product).update()
        try {
            problem(request(a,HttpMethod.GET,"/diary?date=2030-01-01&zone=UTC"),422,"CALCULATION_LIMIT")
        } finally { jdbc.sql("DELETE FROM calorica.diary_entries WHERE product_id=:product").param("product",product).update() }
    }
    private fun awaitBlocked() {
        DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")).use { connection ->
            val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
            connection.createStatement().use { statement ->
                while(System.nanoTime()<deadline) {
                    val count = statement.executeQuery("""SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted
                        AND database=(SELECT oid FROM pg_database WHERE datname=current_database())""").use { rs -> rs.next(); rs.getInt(1) }
                    if(count>0) return
                    Thread.sleep(10)
                }
            }
        }
        fail<Unit>("Diary creation did not wait for the catalog advisory lock")
    }
}
