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
import ru.calorica.catalog.application.*
import ru.calorica.catalog.domain.*
import ru.calorica.catalog.persistence.CatalogRepository
import ru.calorica.catalog.persistence.CompositionRepository
import ru.calorica.diary.application.DiaryService
import ru.calorica.diary.domain.*
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
class DeletionIntegrationTest @Autowired constructor(
    private val catalog: CatalogService, private val diary: DiaryService,
    private val transactions: CatalogTransactions, private val locks: CatalogMutationLock,
    private val repository: CatalogRepository, private val compositions: CompositionRepository,
    private val jdbc: JdbcClient, private val accounts: AccountsService,
    private val http: TestRestTemplate, private val mapper: ObjectMapper,
) {
    private data class User(val actor: Actor, val token: String)
    private lateinit var user: User
    private lateinit var other: User
    private lateinit var admin: User
    @BeforeAll fun setup() {
        fun register(role: Role = Role.USER): User {
            val tokens = accounts.register(Credentials("delete-${UUID.randomUUID()}@example.org", "deletion test password8"))
            if (role == Role.ADMIN) jdbc.sql("UPDATE calorica.accounts SET role='ADMIN' WHERE id=:id").param("id", tokens.account.id).update()
            val session = jdbc.sql("SELECT id FROM calorica.sessions WHERE account_id=:id").param("id", tokens.account.id).query(UUID::class.java).single()
            return User(Actor(tokens.account.id, role, session), tokens.accessToken)
        }
        user = register(); other = register(); admin = register(Role.ADMIN)
    }
    private fun leaf(who: User = user, base: Boolean = false, calories: String = "400") = catalog.create(who.actor,
        "Delete-${UUID.randomUUID()}", Nutrition(calories.toBigDecimal(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), base)
    private fun input(vararg ids: UUID): CompositionWrite {
        val fraction = BigDecimal.ONE.divide(ids.size.toBigDecimal())
        return CompositionWrite(ids.map { ComponentWrite(it, fraction, OptionalFactor()) }, OptionalFactor())
    }
    private fun composite(vararg ids: UUID, who: User = user, base: Boolean = false) = catalog.createComposition(who.actor,
        "Delete-composite-${UUID.randomUUID()}", input(*ids), base)
    private fun write(id: UUID) = DiaryWrite(id, BigDecimal("200"), "g", Instant.parse("2026-11-04T10:00:00Z"), emptyList())
    private fun failure(code: String, block: () -> Unit) = assertThatThrownBy(block)
        .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo(code) }
    private fun request(who: User, method: HttpMethod, path: String, expected: Int): JsonNode? {
        val headers = HttpHeaders().apply { setBearerAuth(who.token) }
        val response = http.exchange("/api/v1$path", method, HttpEntity<Void>(headers), String::class.java)
        assertThat(response.statusCode.value()).describedAs("%s %s: %s", method, path, response.body).isEqualTo(expected)
        return response.body?.takeIf(String::isNotBlank)?.let(mapper::readTree)
    }
    private fun deleted(id: UUID) = jdbc.sql("SELECT is_deleted FROM calorica.products WHERE id=:id")
        .param("id", id).query(Boolean::class.java).single()

    @Test fun `HTTP cascade follows parents once through a diamond preserves children links and diary`() {
        val child = leaf(); val sibling = leaf(calories = "80")
        val target = composite(child.id, sibling.id)
        val left = composite(target.id); val right = composite(target.id)
        val top = composite(left.id, right.id)
        val entries = listOf(target, left, top).map { diary.create(user.actor, write(it.id)) }
        val expected = listOf(target.id, left.id, right.id, top.id).sortedBy(UUID::toString)
        val preview = request(user, HttpMethod.GET, "/products/${target.id}/deletion-preview", 200)!!
        assertThat(preview.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("productId", "cascadeAllowed", "affectedProductIds")
        assertThat(preview["cascadeAllowed"].asBoolean()).isTrue()
        assertThat(preview["affectedProductIds"].map { UUID.fromString(it.asText()) }).containsExactlyElementsOf(expected)
        request(user, HttpMethod.DELETE, "/products/${target.id}?mode=CASCADE", 204)
        expected.forEach { assertThat(deleted(it)).isTrue() }
        assertThat(deleted(child.id)).isFalse(); assertThat(deleted(sibling.id)).isFalse()
        assertThat(catalog.get(user.actor, target.id).components).hasSize(2)
        entries.forEach { before ->
            val after = diary.get(user.actor, before.id)
            assertThat(after.productDeleted).isTrue()
            assertThat(after.nutrition).isEqualTo(before.nutrition)
            assertThat(after.consumedGrams).isEqualTo(before.consumedGrams)
        }
        expected.forEach { id ->
            failure("NOT_FOUND") { diary.create(user.actor, write(id)) }
            failure("NOT_FOUND") { composite(id) }
        }
        val revised = diary.patch(user.actor, entries.first().id, DiaryPatch(quantity = BigDecimal("100"), unit = "g"))
        assertThat(revised.nutrition.calories).isEqualByComparingTo("240")
        catalog.patch(user.actor, child.id, null, Nutrition(BigDecimal("200"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
        assertThat(diary.get(user.actor, entries.first().id).nutrition.calories).isEqualByComparingTo("140")
        request(user, HttpMethod.GET, "/products/${target.id}/deletion-preview", 404)
        request(user, HttpMethod.DELETE, "/products/${target.id}?mode=CASCADE", 404)
    }

    @Test fun `keep preserves ancestors and later cascade walks through a deleted intermediate parent`() {
        val target = leaf(); val middle = composite(target.id); val top = composite(middle.id)
        catalog.delete(user.actor, middle.id, "KEEP_REFERENCES")
        assertThat(catalog.get(user.actor, top.id).nutritionPer100g.calories).isEqualByComparingTo("400")
        assertThat(catalog.writeComposition(user.actor, top.id, input(middle.id)).components).hasSize(1)
        failure("NOT_FOUND") { composite(middle.id) }
        assertThat(catalog.deletionPreview(user.actor, target.id).affectedProductIds).containsExactlyInAnyOrder(target.id, top.id)
        catalog.delete(user.actor, target.id, "CASCADE")
        assertThat(deleted(top.id)).isTrue()
        assertThat(catalog.get(user.actor, top.id).nutritionPer100g.calories).isEqualByComparingTo("400")
    }

    @Test fun `admin cannot reveal or cascade any personal dependencies including deleted and its own`() {
        val target = leaf(admin, true); val baseParent = composite(target.id, who = admin, base = true)
        val personal = composite(baseParent.id, who = other)
        catalog.delete(other.actor, personal.id, "KEEP_REFERENCES")
        assertThat(catalog.deletionPreview(admin.actor, target.id, true).cascadeAllowed).isFalse()
        val ownPersonal = composite(target.id, who = admin)
        val ownBase = leaf(admin, true)
        composite(ownBase.id, who = admin)
        assertThat(catalog.deletionPreview(admin.actor, ownBase.id, true).cascadeAllowed).isFalse()
        failure("FORBIDDEN") { catalog.delete(admin.actor, ownBase.id, "CASCADE", true) }
        val preview = request(admin, HttpMethod.GET, "/admin/products/${target.id}/deletion-preview", 200)!!
        assertThat(preview["cascadeAllowed"].asBoolean()).isFalse()
        assertThat(preview["affectedProductIds"].map { it.asText() }).containsExactlyInAnyOrder(target.id.toString(), baseParent.id.toString())
        assertThat(preview.toString()).doesNotContain(personal.id.toString(), ownPersonal.id.toString(), other.actor.id.toString())
        val problem = request(admin, HttpMethod.DELETE, "/admin/products/${target.id}?mode=CASCADE", 403)!!
        assertThat(problem["code"].asText()).isEqualTo("FORBIDDEN")
        assertThat(problem.toString()).doesNotContain(personal.id.toString(), ownPersonal.id.toString())
        assertThat(deleted(target.id)).isFalse(); assertThat(deleted(baseParent.id)).isFalse(); assertThat(deleted(ownPersonal.id)).isFalse()
        request(admin, HttpMethod.DELETE, "/admin/products/${target.id}?mode=KEEP_REFERENCES", 204)
        assertThat(catalog.get(other.actor, personal.id).nutritionPer100g.calories).isEqualByComparingTo("400")
    }

    @Test fun `pure base cascade is allowed and wrong roles routes targets modes cannot mutate`() {
        val target = leaf(admin, true); val baseParent = composite(target.id, who = admin, base = true); val personal = leaf()
        request(user, HttpMethod.GET, "/admin/products/${target.id}/deletion-preview", 403)
        request(user, HttpMethod.DELETE, "/admin/products/${target.id}?mode=CASCADE", 403)
        request(user, HttpMethod.GET, "/products/${target.id}/deletion-preview", 404)
        request(admin, HttpMethod.GET, "/admin/products/${personal.id}/deletion-preview", 404)
        request(admin, HttpMethod.DELETE, "/admin/products/${personal.id}?mode=CASCADE", 404)
        request(other, HttpMethod.GET, "/products/${personal.id}/deletion-preview", 404)
        request(other, HttpMethod.DELETE, "/products/${personal.id}?mode=CASCADE", 404)
        request(user, HttpMethod.DELETE, "/products/${personal.id}?mode=cascade", 400)
        assertThat(deleted(personal.id)).isFalse()
        request(admin, HttpMethod.DELETE, "/admin/products/${target.id}?mode=CASCADE", 204)
        assertThat(deleted(baseParent.id)).isTrue()
    }

    @Test fun `execution rechecks new dependencies and ownership after an earlier successful preview`() {
        val base = leaf(admin, true)
        assertThat(catalog.deletionPreview(admin.actor, base.id, true).cascadeAllowed).isTrue()
        val personal = composite(base.id)
        failure("FORBIDDEN") { catalog.delete(admin.actor, base.id, "CASCADE", true) }
        assertThat(deleted(base.id)).isFalse(); assertThat(deleted(personal.id)).isFalse()
        val target = leaf()
        assertThat(catalog.deletionPreview(user.actor, target.id).affectedProductIds).containsExactly(target.id)
        val newParent = composite(target.id)
        catalog.delete(user.actor, target.id, "CASCADE")
        assertThat(deleted(newParent.id)).isTrue()
    }

    /** Observe outside the production-sized pool so both contenders can hold a connection. */
    private fun observer() = DriverManager.getConnection(requireNotNull(System.getenv("DB_URL")),
        requireNotNull(System.getenv("DB_USERNAME")), requireNotNull(System.getenv("DB_PASSWORD")))
    private fun awaitBlocked() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        observer().use { connection -> connection.createStatement().use { statement ->
            statement.queryTimeout = 1
            while (System.nanoTime() < deadline) {
                val count = statement.executeQuery("""SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted
                    AND classid IN (1128352847,1128352848)
                    AND database=(SELECT oid FROM pg_database WHERE datname=current_database())""").use { r -> r.next(); r.getInt(1) }
                if (count > 0) return
                Thread.sleep(10)
            }
        } }
        fail<Unit>("Contending transaction never waited on the catalog lock")
    }
    private fun <T> contended(first: (CountDownLatch, CountDownLatch) -> Unit, second: () -> T): T {
        val locked = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val holding = pool.submit(Callable { first(locked, release) })
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue()
            val waiting = pool.submit(Callable { second() })
            awaitBlocked(); release.countDown()
            holding.get(4, TimeUnit.SECONDS)
            return waiting.get(4, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `base cascade waiting for personal dependency commit rechecks and denies without deadlock`() {
        val base = leaf(admin, true); val personal = leaf()
        assertThat(catalog.deletionPreview(admin.actor, base.id, true).cascadeAllowed).isTrue()
        val result = contended({ locked, release -> transactions.mutation { tx ->
            locks.personal(user.actor, tx)
            catalog.requireSelectable(user.actor, base.id, tx)
            compositions.replace(personal.id, listOf(ProductComponent(base.id, BigDecimal.ONE, null)), null)
            locked.countDown(); check(release.await(4, TimeUnit.SECONDS)); Unit
        } }, { runCatching { catalog.delete(admin.actor, base.id, "CASCADE", true) }.exceptionOrNull() })
        assertThat(result).isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("FORBIDDEN") }
        assertThat(deleted(base.id)).isFalse(); assertThat(deleted(personal.id)).isFalse()
        assertThat(catalog.get(user.actor, personal.id).kind).isEqualTo("COMPOSITE")
    }

    @Test fun `dependency and diary creation waiting for base deletion cannot introduce a new hidden reference`() {
        for (diaryWrite in listOf(false, true)) {
            val base = leaf(admin, true)
            val result = contended({ locked, release -> transactions.mutation { tx ->
                locks.base(admin.actor, tx); repository.softDelete(base.id, null)
                locked.countDown(); check(release.await(4, TimeUnit.SECONDS)); Unit
            } }, { runCatching { if (diaryWrite) diary.create(user.actor, write(base.id)) else composite(base.id) }.exceptionOrNull() })
            assertThat(result).isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("NOT_FOUND") }
            assertThat(jdbc.sql("SELECT count(*) FROM calorica.product_components WHERE child_id=:id").param("id", base.id).query(Int::class.java).single()).isZero()
            assertThat(jdbc.sql("SELECT count(*) FROM calorica.diary_entries WHERE product_id=:id").param("id", base.id).query(Int::class.java).single()).isZero()
        }
    }

    @Test fun `cascade waiting on diary selection preserves the committed historical entry`() {
        val target = leaf()
        val entryId = UUID.randomUUID()
        contended({ locked, release -> transactions.mutation { tx ->
            catalog.requireSelectable(user.actor, target.id, tx)
            jdbc.sql("""INSERT INTO calorica.diary_entries(id,owner_id,product_id,consumed_grams,occurred_at)
                VALUES (:id,:owner,:product,200,'2026-11-04T10:00:00Z')""")
                .param("id", entryId).param("owner", user.actor.id).param("product", target.id).update()
            locked.countDown(); check(release.await(4, TimeUnit.SECONDS)); Unit
        } }, { catalog.delete(user.actor, target.id, "CASCADE") })
        assertThat(diary.get(user.actor, entryId).nutrition.calories).isEqualByComparingTo("800")
        assertThat(diary.get(user.actor, entryId).productDeleted).isTrue()
    }

    @Test fun `a database failure during cascade rolls back every product and retains history`() {
        val target = leaf(); val parent = composite(target.id); val entry = diary.create(user.actor, write(parent.id))
        jdbc.sql("""CREATE FUNCTION calorica.t14_reject_delete() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN
            IF NEW.id = ''${parent.id}''::uuid AND NEW.is_deleted THEN RAISE EXCEPTION ''test-only rejection''; END IF;
            RETURN NEW; END'""").update()
        jdbc.sql("CREATE TRIGGER t14_reject_delete BEFORE UPDATE ON calorica.products FOR EACH ROW EXECUTE FUNCTION calorica.t14_reject_delete()").update()
        try {
            val error = request(user, HttpMethod.DELETE, "/products/${target.id}?mode=CASCADE", 500)!!
            assertThat(error.toString()).doesNotContain("test-only rejection", parent.id.toString())
            assertThat(deleted(target.id)).isFalse(); assertThat(deleted(parent.id)).isFalse()
            assertThat(diary.get(user.actor, entry.id)).isEqualTo(entry)
        } finally {
            jdbc.sql("DROP TRIGGER t14_reject_delete ON calorica.products").update()
            jdbc.sql("DROP FUNCTION calorica.t14_reject_delete()").update()
        }
    }

    @Test fun `reachability cap returns no partial preview or mutation and is not bypassed by shared paths`() {
        val target = leaf(); val salt = UUID.randomUUID().toString()
        jdbc.sql("""INSERT INTO calorica.products(id,owner_id,name,manual_calories,manual_protein,manual_fat,manual_carbs)
            SELECT md5(:salt || n)::uuid,:owner,'T14 ancestor cap',1,0,0,0 FROM generate_series(1,10000) n""")
            .param("salt", salt).param("owner", user.actor.id).update()
        jdbc.sql("""INSERT INTO calorica.product_components(parent_id,child_id,fraction)
            SELECT md5(:salt || n)::uuid,:target,1 FROM generate_series(1,10000) n""")
            .param("salt", salt).param("target", target.id).update()
        failure("CALCULATION_LIMIT") { catalog.deletionPreview(user.actor, target.id) }
        failure("CALCULATION_LIMIT") { catalog.delete(user.actor, target.id, "CASCADE") }
        failure("NOT_FOUND") { catalog.deletionPreview(other.actor, target.id) }
        assertThat(deleted(target.id)).isFalse()
        assertThat(jdbc.sql("""SELECT count(*) FROM calorica.products p JOIN calorica.product_components c ON c.parent_id=p.id
            WHERE c.child_id=:id AND p.is_deleted""").param("id", target.id).query(Int::class.java).single()).isZero()
        // KEEP does not need an ancestor traversal and still works for a popular ingredient.
        catalog.delete(user.actor, target.id, "KEEP_REFERENCES")
        assertThat(deleted(target.id)).isTrue()
    }
}
