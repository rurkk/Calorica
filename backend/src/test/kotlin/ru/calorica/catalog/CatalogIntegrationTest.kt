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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.calorica.accounts.application.AccountsService
import ru.calorica.accounts.domain.Credentials
import ru.calorica.catalog.application.*
import ru.calorica.catalog.domain.*
import ru.calorica.catalog.persistence.CatalogRepository
import ru.calorica.platform.security.Actor
import ru.calorica.platform.security.Role
import java.math.BigDecimal
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogIntegrationTest @Autowired constructor(
    private val http: TestRestTemplate, private val mapper: ObjectMapper, private val jdbc: JdbcClient,
    private val accounts: AccountsService, private val catalog: CatalogService,
    private val transactions: CatalogTransactions, private val locks: CatalogMutationLock,
    private val repository: CatalogRepository, private val manager: PlatformTransactionManager,
) {
    data class User(val actor: Actor, val token: String)
    private lateinit var a: User
    private lateinit var b: User
    private lateinit var admin: User
    @BeforeAll fun users() {
        // Real T03 password hashing, sessions and signed access tokens. No auth bypass/security test filter.
        // Service setup avoids consuming the shared HTTP auth-rate-limit bucket of AccountsIntegrationTest.
        fun user(role: Role = Role.USER): User {
            val tokens = accounts.register(Credentials("t05-${UUID.randomUUID()}@example.org", "catalog test password8"))
            if (role == Role.ADMIN) jdbc.sql("UPDATE calorica.accounts SET role='ADMIN' WHERE id=:id").param("id", tokens.account.id).update()
            val session = jdbc.sql("SELECT id FROM calorica.sessions WHERE account_id=:id")
                .param("id", tokens.account.id).query(UUID::class.java).single()
            return User(Actor(tokens.account.id, role, session), tokens.accessToken)
        }
        a = user(); b = user(); admin = user(Role.ADMIN)
    }
    private fun request(user: User?, method: HttpMethod, path: String, body: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON; if (user != null) setBearerAuth(user.token) }
        return http.exchange("/api/v1$path", method, HttpEntity(body, headers), String::class.java)
    }
    private fun json(response: ResponseEntity<String>): JsonNode = mapper.readTree(response.body)
    private fun code(response: ResponseEntity<String>, status: Int, expected: String) {
        assertThat(response.statusCode.value()).isEqualTo(status)
        assertThat(response.headers.contentType.toString()).startsWith("application/problem+json")
        assertThat(json(response)["code"].asText()).isEqualTo(expected)
        assertThat(json(response)["correlationId"].asText()).isEqualTo(response.headers.getFirst("X-Request-Id"))
        assertThat(response.body).doesNotContain("SELECT ", "password_hash", "stackTrace", "catalog test password8")
    }
    private fun body(name: String, calories: String = "123.45"): String = mapper.writeValueAsString(mapOf("name" to name,
        "manualNutrition" to mapOf("calories" to calories, "protein" to "1.23", "fat" to "2.34", "carbs" to "3.45")))
    private fun create(user: User, name: String = "T05-${UUID.randomUUID()}", base: Boolean = false): UUID {
        val result = request(user, HttpMethod.POST, if (base) "/admin/products" else "/products", body(name))
        assertThat(result.statusCode.value()).isEqualTo(201)
        return UUID.fromString(json(result)["id"].asText())
    }
    private fun idList(node: JsonNode): List<UUID> = node["items"].map { UUID.fromString(it["id"].asText()) }

    @Test fun `integrated catalog rejects orphan owner UUID via agent1 migration025`() {
        val id = UUID.randomUUID()
        TransactionTemplate(manager).executeWithoutResult { status ->
            try {
                assertThatThrownBy { jdbc.sql("""INSERT INTO calorica.products
                    (id,owner_id,name,manual_calories,manual_protein,manual_fat,manual_carbs)
                    VALUES (:id,:owner,'orphan test',0,0,0,0)""")
                    .param("id",id).param("owner",UUID.randomUUID()).update() }
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
            } finally { status.setRollbackOnly() }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.products WHERE id=:id").param("id",id)
            .query(Int::class.java).single()).isZero()
    }
    @Test fun `HTTP CRUD uses exact strings PATCH absence and retains deleted history`() {
        code(request(null, HttpMethod.GET, "/products"), 401, "UNAUTHORIZED")
        val id = create(a, "  Личный ${UUID.randomUUID()}  ")
        val first = request(a, HttpMethod.GET, "/products/$id")
        assertThat(json(first)["manualNutrition"]["calories"].isTextual).isTrue()
        assertThat(json(first)["nutritionPer100g"]["calories"].asText()).isEqualTo("123.45")
        assertThat(json(first)["name"].asText()).doesNotStartWith(" ").doesNotEndWith(" ")
        assertThat(json(first)["kind"].asText()).isEqualTo("LEAF")
        assertThat(json(first)["effectiveYieldFactor"].asText()).isEqualTo("1")
        assertThat(json(request(a, HttpMethod.PATCH, "/products/$id", "{}"))).isEqualTo(json(first))
        val patch = request(a, HttpMethod.PATCH, "/products/$id", """{"name":"Изменённое имя"}""")
        assertThat(patch.statusCode.value()).isEqualTo(200)
        assertThat(json(patch)["manualNutrition"]).isEqualTo(json(first)["manualNutrition"])
        val newNutrition = """{"manualNutrition":{"calories":"99999.99","protein":"0","fat":"2.50","carbs":"3.33"}}"""
        assertThat(json(request(a, HttpMethod.PATCH, "/products/$id", newNutrition))["nutritionPer100g"]["calories"].asText()).isEqualTo("99999.99")
        code(request(b, HttpMethod.GET, "/products/$id"),404,"NOT_FOUND")
        code(request(b, HttpMethod.PATCH, "/products/$id", "{}"),404,"NOT_FOUND")
        code(request(b, HttpMethod.DELETE, "/products/$id?mode=KEEP_REFERENCES"),404,"NOT_FOUND")
        code(request(a, HttpMethod.DELETE, "/products/$id?mode=UNKNOWN"),400,"BAD_REQUEST")
        assertThat(request(a, HttpMethod.DELETE, "/products/$id?mode=KEEP_REFERENCES").statusCode.value()).isEqualTo(204)
        assertThat(json(request(a, HttpMethod.GET, "/products/$id"))["isDeleted"].asBoolean()).isTrue()
        assertThat(jdbc.sql("SELECT manual_calories FROM calorica.products WHERE id=:id AND is_deleted")
            .param("id", id).query(BigDecimal::class.java).single()).isEqualByComparingTo("99999.99")
        assertThatThrownBy { transactions.mutation { catalog.requireSelectable(a.actor, id, it) } }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(404) }
        code(request(a, HttpMethod.DELETE, "/products/$id?mode=KEEP_REFERENCES"),404,"NOT_FOUND")
    }
    @Test fun `Admin manages only base products and cannot see another personal row`() {
        val personal = create(a)
        for (path in listOf("/products/$personal", "/admin/products/$personal")) {
            code(request(admin, HttpMethod.PATCH, path, "{}"),404,"NOT_FOUND")
            code(request(admin, HttpMethod.DELETE, "$path?mode=KEEP_REFERENCES"),404,"NOT_FOUND")
        }
        code(request(admin, HttpMethod.GET, "/products/$personal"),404,"NOT_FOUND")
        code(request(a, HttpMethod.POST, "/admin/products", body("Запрещено")),403,"FORBIDDEN")
        val base = create(admin, base = true)
        assertThat(json(request(a, HttpMethod.GET, "/products/$base"))["ownerId"].isNull).isTrue()
        code(request(a, HttpMethod.PATCH, "/products/$base", "{}"),404,"NOT_FOUND")
        assertThat(request(admin, HttpMethod.PATCH, "/admin/products/$base", """{"name":"Базовый изменён"}""").statusCode.value()).isEqualTo(200)
        assertThat(request(admin, HttpMethod.DELETE, "/admin/products/$base?mode=KEEP_REFERENCES").statusCode.value()).isEqualTo(204)
        assertThat(json(request(b, HttpMethod.GET, "/products/$base"))["isDeleted"].asBoolean()).isTrue()
    }
    @Test fun `search ranks exact prefix fuzzy and normalizes names without private leakage`() {
        val root = "search" + UUID.randomUUID().toString().replace("-", "").take(8)
        val exact = create(a, root)
        val prefix = create(a, "$root extended")
        val fuzzy = create(a, root.dropLast(1) + if (root.last() == 'x') "y" else "x")
        val hidden = create(b, root)
        val result = catalog.search(a.actor, root, 100, null)
        assertThat(result.items.map { it.id }).startsWith(exact, prefix).contains(fuzzy).doesNotContain(hidden)
        val normalized = create(a, "СЪЁМКА  СОЛЬ ${UUID.randomUUID()}")
        val own = catalog.get(a.actor, normalized)
        assertThat(catalog.search(a.actor, ProductNames.normalize(own.name),100,null).items.map { it.id }).contains(normalized)
        val short = create(a, "ю")
        val nonPrefix = create(a, "ую")
        assertThat(catalog.search(a.actor, "ю",100,null).items.map { it.id }).contains(short).doesNotContain(nonPrefix)
        val percent = create(a, "q%")
        val other = create(a, "qabcdef")
        assertThat(catalog.search(a.actor, "q%",100,null).items.map { it.id }).contains(percent).doesNotContain(other)
        assertThat(repository.normalize(" \tСЪЁМКА  Соль\nМОЛОКО  ")).isEqualTo(ProductNames.normalize(" \tСЪЁМКА  Соль\nМОЛОКО  "))
    }
    @Test fun `cursor traverses one stable catalog and is bound to actor query and valid keys`() {
        val expected = catalog.search(a.actor, null,100,null).items.map { it.id }.toSet()
        var cursor: String? = null
        val ids = mutableListOf<UUID>()
        do {
            val page = catalog.search(a.actor, null,7,cursor)
            ids.addAll(page.items.map { it.id }); cursor = page.nextCursor
        } while (cursor != null)
        assertThat(ids).doesNotHaveDuplicates(); assertThat(ids.toSet()).isEqualTo(expected)
        val first = catalog.search(a.actor, null,1,null)
        val opaque = requireNotNull(first.nextCursor)
        for (operation in listOf<() -> Any>(
            { catalog.search(b.actor,null,1,opaque) }, { catalog.search(a.actor,"different",1,opaque) },
            { catalog.search(a.actor,null,1,"!") }, { catalog.search(a.actor,null,0,null) },
            { catalog.search(a.actor,null,101,null) }, { catalog.search(a.actor,"я".repeat(201),1,null) }))
            assertThatThrownBy { operation() }.isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(400) }
    }
    @Test fun `invalid writes are atomic and do not accept client ownership or numeric coercion`() {
        val id = create(a)
        val before = catalog.get(a.actor,id)
        val bad = listOf("""{"name":null}""", """{"manualNutrition":null}""", """{"name":" "}""",
            """{"manualNutrition":{"calories":"1.001","protein":"0","fat":"0","carbs":"0"}}""")
        bad.forEach { assertThat(request(a,HttpMethod.PATCH,"/products/$id",it).statusCode.value()).isEqualTo(422) }
        code(request(a,HttpMethod.PATCH,"/products/$id","""{"ownerId":"${b.actor.id}"}"""),400,"BAD_REQUEST")
        code(request(a,HttpMethod.POST,"/products", """{"name":"Состав","composition":{}}"""),422,"INVALID_COMPOSITION")
        code(request(a,HttpMethod.POST,"/products", """{"name":"Число","manualNutrition":{"calories":12,"protein":"0","fat":"0","carbs":"0"}}"""),400,"BAD_REQUEST")
        code(request(a,HttpMethod.POST,"/products",body("Выход диапазона","100000")),422,"VALIDATION_FAILED")
        assertThat(catalog.get(a.actor,id)).isEqualTo(before)
    }
    @Test fun `public calculation uses SQL preserves portions and reads retained deleted leaves`() {
        val id = create(a)
        catalog.delete(a.actor,id,"KEEP_REFERENCES")
        transactions.snapshot { snapshot ->
            val nutrition = catalog.nutrition(a.actor,listOf(id),snapshot).getValue(id)
            assertThat(nutrition.nutritionPer100g.calories).isEqualByComparingTo("123.45")
            val allocations = catalog.allocateLeaves(a.actor,listOf(CatalogPortion(0,id,BigDecimal("123.456")),
                CatalogPortion(1,id,BigDecimal("20.001"))),snapshot)
            assertThat(allocations.map { it.allocatedGrams }).containsExactly(BigDecimal("123.456"),BigDecimal("20.001"))
            assertThat(allocations.map { it.rootOrdinal }).containsExactly(0,1)
            Unit
        }
        assertThatThrownBy { transactions.snapshot { catalog.nutrition(b.actor,listOf(id),it) } }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(404) }
    }
    @Test fun `transaction capabilities cannot reuse released locks or stale snapshots`() {
        val id = create(a)
        val staleWrite = transactions.mutation { tx -> catalog.requireSelectable(a.actor,id,tx); tx }
        assertThatThrownBy { transactions.mutation { catalog.requireSelectable(a.actor,id,staleWrite) } }
            .isInstanceOf(IllegalStateException::class.java)
        val staleRead = transactions.snapshot { it }
        assertThatThrownBy { transactions.snapshot { catalog.nutrition(a.actor,listOf(id),staleRead) } }
            .isInstanceOf(IllegalStateException::class.java)
    }
    @Test fun `capability cannot cross suspended physical transaction in REQUIRES_NEW`() {
        val id = create(a)
        transactions.mutation { outer ->
            catalog.requireSelectable(a.actor,id,outer)
            val inner = TransactionTemplate(manager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            }
            assertThatThrownBy { inner.execute { catalog.requireSelectable(a.actor,id,outer) } }
                .isInstanceOf(IllegalStateException::class.java)
            // The resumed original transaction still owns the locks.
            assertThat(catalog.requireSelectable(a.actor,id,outer).id).isEqualTo(id)
        }
        transactions.snapshot { outer ->
            val inner = TransactionTemplate(manager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                isReadOnly = true
            }
            assertThatThrownBy { inner.execute { catalog.nutrition(a.actor,listOf(id),outer) } }
                .isInstanceOf(IllegalStateException::class.java)
            catalog.nutrition(a.actor,listOf(id),outer)
        }
    }
    @Test fun `noncanonical UUIDs in paths and opaque cursor are bad requests`() {
        code(request(a,HttpMethod.GET,"/products/1-1-1-1-1"),400,"BAD_REQUEST")
        val cursor = requireNotNull(catalog.search(a.actor,null,1,null).nextCursor)
        val node = mapper.readTree(java.util.Base64.getUrlDecoder().decode(cursor)) as com.fasterxml.jackson.databind.node.ObjectNode
        node.put("id","1-1-1-1-1")
        val invalid = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(node))
        assertThatThrownBy { catalog.search(a.actor,null,1,invalid) }.isInstanceOfSatisfying(CatalogFailure::class.java) {
            assertThat(it.status).isEqualTo(400)
        }
    }
    /** Observe the test database outside the two-connection application pool used by CI/prod. */
    private fun observer() = DriverManager.getConnection(requireNotNull(System.getenv("DB_URL")),
        requireNotNull(System.getenv("DB_USERNAME")), requireNotNull(System.getenv("DB_PASSWORD")))
    private fun awaitBlocked() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        observer().use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = 1
                while (System.nanoTime() < deadline) {
                    val n = statement.executeQuery("""SELECT count(*) FROM pg_locks WHERE locktype='advisory'
                        AND NOT granted AND classid IN (1128352847,1128352848)
                        AND database=(SELECT oid FROM pg_database WHERE datname=current_database())""").use { r -> r.next(); r.getInt(1) }
                    if (n > 0) return
                    Thread.sleep(10)
                }
            }
        }
        fail<Unit>("Contending PostgreSQL transaction never waited for advisory lock")
    }
    private fun observedDeleted(id: UUID): Boolean = observer().use { connection ->
        connection.prepareStatement("SELECT is_deleted FROM calorica.products WHERE id=?").use { statement ->
            statement.setObject(1,id)
            statement.executeQuery().use { r -> check(r.next()); r.getBoolean(1) }
        }
    }
    @Test fun `selection waiting for personal deletion sees committed deletion after lock`() {
        val id = create(a)
        val deleted = CountDownLatch(1); val commit = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val deleting = pool.submit(Callable { transactions.mutation { tx ->
                locks.personal(a.actor,tx); repository.softDelete(id,a.actor.id); deleted.countDown()
                check(commit.await(5,TimeUnit.SECONDS)); Unit
            } })
            assertThat(deleted.await(3,TimeUnit.SECONDS)).isTrue()
            val selecting = pool.submit(Callable { runCatching { transactions.mutation { catalog.requireSelectable(a.actor,id,it) } }.exceptionOrNull() })
            awaitBlocked(); commit.countDown(); deleting.get(3,TimeUnit.SECONDS)
            assertThat(selecting.get(3,TimeUnit.SECONDS)).isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(404) }
            assertThat(catalog.get(a.actor,id).isDeleted).isTrue()
        } finally { commit.countDown(); pool.shutdownNow() }
    }
    @Test fun `base deletion waits for shared base selection transaction`() {
        val id = create(admin,base=true)
        val selected = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val selecting = pool.submit(Callable { transactions.mutation { tx ->
                catalog.requireSelectable(a.actor,id,tx); selected.countDown(); check(release.await(5,TimeUnit.SECONDS)); Unit
            } })
            assertThat(selected.await(3,TimeUnit.SECONDS)).isTrue()
            val deleting = pool.submit(Callable { catalog.delete(admin.actor,id,"KEEP_REFERENCES",true) })
            awaitBlocked()
            assertThat(observedDeleted(id)).isFalse()
            release.countDown(); selecting.get(3,TimeUnit.SECONDS); deleting.get(3,TimeUnit.SECONDS)
            assertThat(catalog.get(a.actor,id).isDeleted).isTrue()
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `lock timeout returns safe 503 rolls back and leaves product selectable`() {
        val id = create(a)
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val holding = pool.submit(Callable { transactions.mutation { tx ->
                locks.personal(a.actor,tx); locked.countDown(); check(release.await(6,TimeUnit.SECONDS)); Unit
            } })
            assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue()
            code(request(a,HttpMethod.DELETE,"/products/$id?mode=KEEP_REFERENCES"),503,"SERVICE_UNAVAILABLE")
            release.countDown(); holding.get(3,TimeUnit.SECONDS)
            assertThat(catalog.get(a.actor,id).isDeleted).isFalse()
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
