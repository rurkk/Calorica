package ru.calorica.catalog.labels

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
import ru.calorica.catalog.application.labels.*
import ru.calorica.catalog.domain.*
import ru.calorica.catalog.persistence.labels.ProductLabelsRepository
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.liquibase.change-log=classpath:db/changelog/product-labels-test.yaml"])
class ProductLabelsIntegrationTest @Autowired constructor(
    private val catalog: CatalogService, private val transactions: CatalogTransactions, private val locks: CatalogMutationLock,
    private val labels: ProductLabels, private val distribution: ProductLabelRead, private val repository: ProductLabelsRepository,
    private val jdbc: JdbcClient, private val accounts: AccountsService, private val http: TestRestTemplate, private val mapper: ObjectMapper,
) {
    data class User(val actor: Actor, val token: String)
    private lateinit var a: User; private lateinit var b: User; private lateinit var admin: User
    @BeforeAll fun users() {
        fun user(role: Role = Role.USER): User {
            val value = accounts.register(Credentials("labels-${UUID.randomUUID()}@example.org", "labels test password8"))
            if (role == Role.ADMIN) jdbc.sql("UPDATE calorica.accounts SET role='ADMIN' WHERE id=:id").param("id", value.account.id).update()
            val session = jdbc.sql("SELECT id FROM calorica.sessions WHERE account_id=:id").param("id", value.account.id)
                .query(UUID::class.java).single()
            return User(Actor(value.account.id, role, session), value.accessToken)
        }
        a=user(); b=user(); admin=user(Role.ADMIN)
    }
    private fun leaf(user: User = a, base: Boolean = false, calories: String = "400") = catalog.create(user.actor,
        "T13-${UUID.randomUUID()}", Nutrition(calories.toBigDecimal(), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE), base)
    private fun label() = labels.create(admin.actor, "Group-${UUID.randomUUID()}")
    private fun composition(vararg components: Triple<UUID, String, String?>, manual: String? = null): CompositionWrite =
        CompositionInput.parse(mapper.valueToTree(mapOf("inputMode" to "PERCENT", "components" to components.map { (id, amount, factor) ->
            mapOf("productId" to id, "percent" to amount, "yieldFactor" to factor) }, "manualYieldFactor" to manual)))
    private fun grams(id: UUID, consumed: String = "200", user: User = a) = transactions.snapshot {
        distribution.distribution(user.actor, listOf(CatalogPortion(0, id, consumed.toBigDecimal())), it).associate { it.labelId to it.grams }
    }
    private fun request(user: User?, method: HttpMethod, path: String, body: Any? = null, status: Int = 200): JsonNode {
        val result = http.exchange("/api/v1$path", method, HttpEntity(body?.let(mapper::writeValueAsString), HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON; user?.let { setBearerAuth(it.token) }
        }), String::class.java)
        assertThat(result.statusCode.value()).describedAs("%s %s: %s", method, path, result.body).isEqualTo(status)
        if (status == 204) { assertThat(result.body).isNullOrEmpty(); return mapper.nullNode() }
        val node = mapper.readTree(result.body)
        if (status >= 400) {
            assertThat(result.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
            assertThat(node["correlationId"].asText()).isEqualTo(result.headers.getFirst("X-Request-Id"))
        }
        return node
    }
    private fun failure(code: String, block: () -> Unit) = assertThatThrownBy(block)
        .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo(code) }

    @Test fun `common labels are visible but only Admin can create rename and delete via actual API`() {
        request(null, HttpMethod.GET, "/product-labels", status = 401)
        assertThat(request(a, HttpMethod.GET, "/product-labels").map { it["name"].asText() })
            .contains("Молочные продукты", "Мясо", "Рыба и морепродукты", "Яйца", "Крупы и зерновые", "Овощи и фрукты", "Бобовые", "Сладкое")
        request(a, HttpMethod.POST, "/admin/product-labels", mapOf("name" to "Denied"), 403)
        val created = request(admin, HttpMethod.POST, "/admin/product-labels", mapOf("name" to "  New group  "), 201)
        assertThat(created["name"].asText()).isEqualTo("New group")
        val path = "/admin/product-labels/${created["id"].asText()}"
        request(a, HttpMethod.PATCH, path, mapOf("name" to "Denied"), 403)
        request(a, HttpMethod.DELETE, path, status = 403)
        assertThat(request(admin, HttpMethod.PATCH, path, mapOf("name" to "Renamed"))["name"].asText()).isEqualTo("Renamed")
        assertThat(request(b, HttpMethod.GET, "/product-labels").any { it["id"] == created["id"] && it["name"].asText() == "Renamed" }).isTrue()
        request(admin, HttpMethod.DELETE, path, status = 204)
        request(admin, HttpMethod.DELETE, path, status = 404)
        request(admin, HttpMethod.PATCH, path, mapOf("name" to "Gone"), 404)
    }

    @Test fun `assignment enforces personal owner and base Admin scope including Admin privacy`() {
        val personal = leaf(); val base = leaf(admin, true); val group = label()
        val input = mapOf("labelIds" to listOf(group.id))
        request(b, HttpMethod.PUT, "/products/${personal.id}/labels", input, 404)
        request(admin, HttpMethod.PUT, "/products/${personal.id}/labels", input, 404)
        request(admin, HttpMethod.PUT, "/admin/products/${personal.id}/labels", input, 404)
        request(admin, HttpMethod.GET, "/products/${personal.id}", status = 404)
        failure("NOT_FOUND") { grams(personal.id, user = admin) }
        request(a, HttpMethod.PUT, "/products/${base.id}/labels", input, 404)
        request(a, HttpMethod.PUT, "/admin/products/${base.id}/labels", input, 403)
        request(admin, HttpMethod.PUT, "/products/${base.id}/labels", input, 404)
        assertThat(request(admin, HttpMethod.PUT, "/admin/products/${base.id}/labels", input)["productLabelIds"].map { it.asText() })
            .containsExactly(group.id.toString())
        assertThat(request(a, HttpMethod.PUT, "/products/${personal.id}/labels", input)["productLabelIds"].map { it.asText() })
            .containsExactly(group.id.toString())
        assertThat(request(b, HttpMethod.GET, "/products/${base.id}")["productLabelIds"].map { it.asText() }).containsExactly(group.id.toString())
        assertThat(catalog.search(a.actor, personal.name, 20, null).items.single().productLabelIds).containsExactly(group.id)
        catalog.delete(a.actor, personal.id, "KEEP_REFERENCES")
        request(a, HttpMethod.PUT, "/products/${personal.id}/labels", input, 404)
        assertThat(catalog.get(a.actor, personal.id).productLabelIds).containsExactly(group.id)
    }

    @Test fun `invalid assignment rolls back and empty explicit set clears links`() {
        val p = leaf(); val group = label(); val path = "/products/${p.id}/labels"
        labels.assign(a.actor, p.id, listOf(group.id))
        for ((body, status) in listOf(
            mapOf("labelIds" to listOf(group.id, UUID.randomUUID())) to 404,
            mapOf("labelIds" to listOf(group.id, group.id)) to 422,
            mapOf("labelIds" to List(101) { UUID.randomUUID() }) to 422,
            mapOf("labelIds" to null) to 422,
            mapOf("labelIds" to listOf("bad")) to 400,
            mapOf("labelIds" to emptyList<String>(), "ownerId" to b.actor.id) to 400)) {
            request(a, HttpMethod.PUT, path, body, status)
            assertThat(catalog.get(a.actor, p.id).productLabelIds).containsExactly(group.id)
        }
        assertThat(request(a, HttpMethod.PUT, path, mapOf("labelIds" to emptyList<String>()))["productLabelIds"]).isEmpty()
        assertThat(grams(p.id)).isEmpty()
    }

    @Test fun `different ingredient yields allocate 50 and 150 ready grams with overlaps and unchanged diary total`() {
        val first = leaf(); val second = leaf(calories = "80"); val one = label(); val two = label(); val common = label()
        labels.assign(a.actor, first.id, listOf(one.id, common.id)); labels.assign(a.actor, second.id, listOf(two.id, common.id))
        val parent = catalog.createComposition(a.actor, "Cooked", composition(Triple(first.id, "50", "0.5"), Triple(second.id, "50", "1.5")))
        val entry = request(a, HttpMethod.POST, "/diary/entries", mapOf("productId" to parent.id, "quantity" to "200", "unit" to "g",
            "occurredAt" to "2026-10-25T08:00:00Z", "mealLabelIds" to emptyList<String>()), 201)
        assertThat(entry["nutrition"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("480")
        val values = grams(UUID.fromString(entry["productId"].asText()), entry["consumedGrams"].asText())
        assertThat(values[one.id]).isEqualByComparingTo("50"); assertThat(values[two.id]).isEqualByComparingTo("150")
        assertThat(values[common.id]).isEqualByComparingTo("200")
        assertThat(request(a, HttpMethod.GET, "/diary?date=2026-10-25&zone=UTC")["total"]["calories"].asText().toBigDecimal())
            .isEqualByComparingTo("480")
        labels.assign(a.actor, first.id, listOf(two.id))
        assertThat(grams(parent.id)[two.id]).isEqualByComparingTo("200")
        assertThat(grams(parent.id)).doesNotContainKey(one.id)
        assertThat(grams(parent.id)[common.id]).isEqualByComparingTo("150")
    }

    @Test fun `manual yield distributes 120 and 80 and parent labels survive both kind transitions ignored while composite`() {
        val first = leaf(); val second = leaf(); val parent = leaf(); val one = label(); val two = label(); val parentGroup = label()
        labels.assign(a.actor, first.id, listOf(one.id)); labels.assign(a.actor, second.id, listOf(two.id))
        labels.assign(a.actor, parent.id, listOf(parentGroup.id))
        val input = composition(Triple(first.id, "60", null), Triple(second.id, "40", null), manual = "0.8")
        assertThat(catalog.writeComposition(a.actor, parent.id, input).productLabelIds).containsExactly(parentGroup.id)
        val values = grams(parent.id)
        assertThat(values[one.id]).isEqualByComparingTo("120"); assertThat(values[two.id]).isEqualByComparingTo("80")
        assertThat(values).doesNotContainKey(parentGroup.id)
        labels.rename(admin.actor, one.id, "Retrospective name")
        assertThat(grams(parent.id)[one.id]).isEqualByComparingTo("120")
        val back = catalog.writeComposition(a.actor, parent.id, composition())
        assertThat(back.kind).isEqualTo("LEAF"); assertThat(back.productLabelIds).containsExactly(parentGroup.id)
        assertThat(grams(parent.id)[parentGroup.id]).isEqualByComparingTo("200")
    }

    @Test fun `nested common paths duplicate portions and unlabeled leaves are handled only by authoritative allocation`() {
        val first = leaf(); val second = leaf(); val group = label(); val parentGroup = label()
        labels.assign(a.actor, first.id, listOf(group.id))
        val child = catalog.createComposition(a.actor, "Child", composition(Triple(first.id,"50",null), Triple(second.id,"50",null), manual="0.5"))
        val parent = catalog.createComposition(a.actor, "Parent", composition(Triple(child.id,"50","0.5"), Triple(first.id,"50","1.5")))
        labels.assign(a.actor, child.id, listOf(parentGroup.id)); labels.assign(a.actor, parent.id, listOf(parentGroup.id))
        transactions.snapshot { snapshot ->
            val portions = listOf(CatalogPortion(7,parent.id,BigDecimal("200")), CatalogPortion(11,parent.id,BigDecimal("100")))
            val leaves = catalog.allocateLeaves(a.actor, portions, snapshot)
            assertThat(leaves.sumOf { it.allocatedGrams }).isEqualByComparingTo("300")
            val values = distribution.distribution(a.actor, portions, snapshot)
            assertThat(values.map { it.labelId }).containsExactly(group.id)
            assertThat(values.single().grams).isEqualByComparingTo("262.5")
            true
        }
        labels.delete(admin.actor, group.id)
        assertThat(grams(parent.id)).isEmpty()
        assertThat(catalog.get(a.actor, first.id).productLabelIds).isEmpty()
    }

    @Test fun `deleting label removes all personal base and composite links while preserving products`() {
        val p = leaf(); val base = leaf(admin,true); val parent = leaf(); val group = label()
        labels.assign(a.actor,p.id,listOf(group.id)); labels.assign(admin.actor,base.id,listOf(group.id),true)
        labels.assign(a.actor,parent.id,listOf(group.id)); catalog.writeComposition(a.actor,parent.id,composition(Triple(p.id,"100",null)))
        request(admin,HttpMethod.DELETE,"/admin/product-labels/${group.id}",status=204)
        for (id in listOf(p.id,base.id,parent.id)) assertThat(catalog.get(a.actor,id).productLabelIds).isEmpty()
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.product_label_links WHERE label_id=:id").param("id",group.id).query(Int::class.java).single()).isZero()
        assertThat(grams(parent.id)).isEmpty()
    }

    @Test fun `one caller snapshot retains nutrition allocations and label links across concurrent mutations`() {
        val first = leaf(); val second = leaf(calories="80"); val group = label(); val replacement = label()
        labels.assign(a.actor,first.id,listOf(group.id))
        val parent = catalog.createComposition(a.actor,"Snapshot",composition(Triple(first.id,"50","0.5"),Triple(second.id,"50","1.5")))
        val pool = Executors.newSingleThreadExecutor()
        try {
            transactions.snapshot { snapshot ->
                val portions = listOf(CatalogPortion(0,parent.id,BigDecimal("200")))
                assertThat(catalog.nutrition(a.actor,listOf(parent.id),snapshot)[parent.id]!!.nutritionPer100g.calories).isEqualByComparingTo("240")
                pool.submit {
                    labels.assign(a.actor,first.id,listOf(replacement.id))
                    catalog.writeComposition(a.actor,parent.id,composition(Triple(first.id,"50","1.5"),Triple(second.id,"50","0.5")))
                    labels.delete(admin.actor,group.id)
                }.get(3,TimeUnit.SECONDS)
                val old = distribution.distribution(a.actor,portions,snapshot)
                assertThat(old.map { it.labelId }).containsExactly(group.id)
                assertThat(old.single().grams).isEqualByComparingTo("50")
                true
            }
            assertThat(grams(parent.id).keys).containsExactly(replacement.id)
            assertThat(grams(parent.id)[replacement.id]).isEqualByComparingTo("150")
        } finally { pool.shutdownNow() }
    }

    @Test fun `distribution rejects expired snapshot and private products even with empty label groups`() {
        val p = leaf()
        val expired = transactions.snapshot { it }
        assertThatThrownBy { distribution.distribution(a.actor,emptyList(),expired) }.isInstanceOf(IllegalStateException::class.java)
        failure("NOT_FOUND") { grams(p.id,user=b) }
        assertThat(transactions.snapshot { distribution.distribution(a.actor,emptyList(),it) }).isEmpty()
    }

    @Test fun `large projection uses SQL exact NUMERIC grouping beyond the parameter count of a VALUES batch`() {
        val leaves=List(4) { leaf() }; val group=label()
        leaves.forEach { labels.assign(a.actor,it.id,listOf(group.id)) }
        val amount=BigDecimal("0.00000000000000000012345678901234567890")
        val rows=List(36000) { LeafAllocation(it/4,leaves[it%4].id,amount) }
        val grouped=transactions.snapshot { repository.distribution(rows) }
        assertThat(grouped.map { it.labelId }).containsExactly(group.id)
        assertThat(grouped.single().grams).isEqualByComparingTo("0.00000000000000444444440444444444040000")
    }

    @Test fun `label deletion waits for personal assignment then removes the newly committed link`() {
        val p=leaf(); val group=label(); val changed=CountDownLatch(1); val commit=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val assigning=pool.submit { transactions.mutation { tx ->
                locks.personal(a.actor,tx); repository.replace(p.id,listOf(group.id))
                changed.countDown(); check(commit.await(5,TimeUnit.SECONDS)); Unit
            } }
            assertThat(changed.await(3,TimeUnit.SECONDS)).isTrue()
            val deleting=pool.submit { labels.delete(admin.actor,group.id) }
            awaitBlocked(1128352847); commit.countDown(); assigning.get(3,TimeUnit.SECONDS); deleting.get(3,TimeUnit.SECONDS)
            assertThat(catalog.get(a.actor,p.id).productLabelIds).isEmpty()
            assertThat(grams(p.id)).isEmpty()
        } finally { commit.countDown(); pool.shutdownNow() }
    }

    @Test fun `assignment waiting for label deletion validates after base lock and cannot revive deleted relation`() {
        val p = leaf(); val group = label(); labels.assign(a.actor,p.id,listOf(group.id))
        val changed=CountDownLatch(1); val commit=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val deleting=pool.submit { transactions.mutation { tx ->
                locks.base(admin.actor,tx); repository.delete(group.id); changed.countDown(); check(commit.await(5,TimeUnit.SECONDS)); Unit
            } }
            assertThat(changed.await(3,TimeUnit.SECONDS)).isTrue()
            val assigning=pool.submit<String> { try { labels.assign(a.actor,p.id,listOf(group.id)); "OK" } catch(e:CatalogFailure) { e.code } }
            awaitBlocked(1128352847); commit.countDown(); deleting.get(3,TimeUnit.SECONDS)
            assertThat(assigning.get(3,TimeUnit.SECONDS)).isEqualTo("NOT_FOUND")
            assertThat(catalog.get(a.actor,p.id).productLabelIds).isEmpty()
        } finally { commit.countDown(); pool.shutdownNow() }
    }

    @Test fun `assignment waiting for product deletion rereads visibility after owner lock`() {
        val p=leaf(); val group=label(); val changed=CountDownLatch(1); val commit=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val deleting=pool.submit { transactions.mutation { tx ->
                locks.personal(a.actor,tx); jdbc.sql("UPDATE calorica.products SET is_deleted=true WHERE id=:id").param("id",p.id).update()
                changed.countDown(); check(commit.await(5,TimeUnit.SECONDS)); Unit
            } }
            assertThat(changed.await(3,TimeUnit.SECONDS)).isTrue()
            val assigning=pool.submit<String> { try { labels.assign(a.actor,p.id,listOf(group.id)); "OK" } catch(e:CatalogFailure) { e.code } }
            awaitBlocked(1128352848); commit.countDown(); deleting.get(3,TimeUnit.SECONDS)
            assertThat(assigning.get(3,TimeUnit.SECONDS)).isEqualTo("NOT_FOUND")
            assertThat(catalog.get(a.actor,p.id).productLabelIds).isEmpty()
        } finally { commit.countDown(); pool.shutdownNow() }
    }
    private fun awaitBlocked(namespace: Int) {
        DriverManager.getConnection(requireNotNull(System.getenv("DB_URL")),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")).use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout=1
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
                while(System.nanoTime()<deadline) {
                    val waiting=statement.executeQuery("""SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted
                        AND classid=$namespace AND database=(SELECT oid FROM pg_database WHERE datname=current_database())""").use { it.next();it.getInt(1) }
                    if(waiting>0) return
                    Thread.sleep(10)
                }
            }
        }
        fail<Unit>("Label assignment did not wait for the expected advisory lock")
    }
}
