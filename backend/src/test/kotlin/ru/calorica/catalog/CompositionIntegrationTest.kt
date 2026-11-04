package ru.calorica.catalog

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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompositionIntegrationTest @Autowired constructor(
    private val catalog: CatalogService, private val transactions: CatalogTransactions,
    private val jdbc: JdbcClient, private val accounts: AccountsService,
    private val http: TestRestTemplate, private val mapper: ObjectMapper,
) {
    private lateinit var actor: Actor
    private lateinit var other: Actor
    private lateinit var token: String
    @BeforeAll fun setup() {
        fun register(): Pair<Actor, String> {
            val tokens = accounts.register(Credentials("graph-${UUID.randomUUID()}@example.org", "graph test password8"))
            val session = jdbc.sql("SELECT id FROM calorica.sessions WHERE account_id=:id").param("id", tokens.account.id).query(UUID::class.java).single()
            return Actor(tokens.account.id, Role.USER, session) to tokens.accessToken
        }
        val user = register(); actor = user.first; token = user.second; other = register().first
    }
    private fun leaf(calories: String = "400", owner: Actor = actor): Product = catalog.create(owner, "Graph-${UUID.randomUUID()}",
        Nutrition(BigDecimal(calories), BigDecimal("10"), BigDecimal("2"), BigDecimal("3")))
    private fun input(vararg children: Pair<UUID,String>, manual: String? = null): CompositionWrite = CompositionWrite(
        children.map { ComponentWrite(it.first, it.second.toBigDecimal(), OptionalFactor()) },
        if (manual == null) OptionalFactor() else OptionalFactor(true, manual.toBigDecimal()))
    private fun composite(vararg children: Pair<UUID,String>, manual: String? = null): Product = catalog.createComposition(actor,
        "Composite-${UUID.randomUUID()}", input(*children,manual=manual))
    private fun write(id: UUID, body: String) = catalog.writeComposition(actor,id,CompositionInput.parse(mapper.readTree(body)))
    private fun failure(code: String, block: () -> Unit) {
        assertThatThrownBy(block).isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo(code) }
    }
    @Test fun `composite POST is atomic nullable nutrition and switching to leaf requires real manual values`() {
        val a = leaf(); val body = """{"name":"New composite","composition":{"inputMode":"PERCENT","components":[{"productId":"${a.id}","percent":"100"}]}}"""
        val headers = HttpHeaders().apply { setBearerAuth(token); contentType=MediaType.APPLICATION_JSON }
        val response = http.exchange("/api/v1/products",HttpMethod.POST,HttpEntity(body,headers),String::class.java)
        assertThat(response.statusCode.value()).isEqualTo(201)
        val result = mapper.readTree(response.body); val id = UUID.fromString(result["id"].asText())
        assertThat(result["manualNutrition"].isNull).isTrue()
        assertThat(result["nutritionPer100g"]["calories"].asText().toBigDecimal()).isEqualByComparingTo("400")
        failure("INVALID_COMPOSITION") { write(id,"""{"inputMode":"PERCENT","components":[]}""") }
        assertThat(catalog.get(actor,id).kind).isEqualTo("COMPOSITE")
        val back = write(id,"""{"inputMode":"PERCENT","components":[],"manualNutrition":{"calories":"75","protein":"1","fat":"2","carbs":"3"}}""")
        assertThat(back.kind).isEqualTo("LEAF"); assertThat(back.manualYieldFactor).isNull()
        assertThat(back.nutritionPer100g.calories).isEqualByComparingTo("75")
        val before = jdbc.sql("SELECT count(*) FROM calorica.products WHERE owner_id=:owner").param("owner",actor.id).query(Int::class.java).single()
        failure("NOT_FOUND") { composite(UUID.randomUUID() to "1") }
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.products WHERE owner_id=:owner").param("owner",actor.id).query(Int::class.java).single()).isEqualTo(before)
    }
    @Test fun `common leaf counts every path and manual output does not inflate ready grams`() {
        val a = leaf("400"); val b = leaf("80")
        val left = composite(a.id to "0.6", b.id to "0.4", manual="0.8")
        val right = composite(a.id to "1")
        val parent = composite(left.id to "0.5",right.id to "0.5")
        assertThat(left.nutritionPer100g.calories).isEqualByComparingTo("340")
        assertThat(parent.nutritionPer100g.calories).isEqualByComparingTo("370")
        val allocated = transactions.snapshot { catalog.allocateLeaves(actor,listOf(CatalogPortion(7,left.id,BigDecimal("200")),
            CatalogPortion(8,parent.id,BigDecimal("200"))),it) }
        assertThat(allocated.single { it.rootOrdinal==7 && it.leafId==a.id }.allocatedGrams).isEqualByComparingTo("120")
        assertThat(allocated.single { it.rootOrdinal==7 && it.leafId==b.id }.allocatedGrams).isEqualByComparingTo("80")
        assertThat(allocated.single { it.rootOrdinal==8 && it.leafId==a.id }.allocatedGrams).isEqualByComparingTo("160")
        catalog.patch(actor,a.id,null,Nutrition(BigDecimal("200"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO))
        assertThat(catalog.get(actor,parent.id).nutritionPer100g.calories).isEqualByComparingTo("195")
    }
    @Test fun `hidden edge yields survive percentage edits and clearing last factor never resurrects manual output`() {
        val a = leaf("400"); val b = leaf("80"); val p = composite(a.id to "0.5",b.id to "0.5",manual="0.8")
        var result = write(p.id,"""{"inputMode":"PERCENT","components":[{"productId":"${a.id}","percent":"50","yieldFactor":"0.5"},{"productId":"${b.id}","percent":"50","yieldFactor":"1.5"}]}""")
        assertThat(result.manualYieldFactor).isNull(); assertThat(result.yieldSource).isEqualTo("INGREDIENTS")
        assertThat(result.nutritionPer100g.calories).isEqualByComparingTo("240")
        val allocations = transactions.snapshot { catalog.allocateLeaves(actor,listOf(CatalogPortion(0,p.id,BigDecimal("200"))),it) }
        assertThat(allocations.single { it.leafId==a.id }.allocatedGrams).isEqualByComparingTo("50")
        assertThat(allocations.single { it.leafId==b.id }.allocatedGrams).isEqualByComparingTo("150")
        result = write(p.id,"""{"inputMode":"PERCENT","components":[{"productId":"${a.id}","percent":"50"},{"productId":"${b.id}","percent":"50","yieldFactor":null}]}""")
        assertThat(result.nutritionPer100g.calories).isEqualByComparingTo("320")
        failure("YIELD_MODE_CONFLICT") { write(p.id,"""{"inputMode":"PERCENT","manualYieldFactor":"0.8","components":[{"productId":"${a.id}","percent":"100"}]}""") }
        assertThat(catalog.get(actor,p.id).components).hasSize(2)
        result = write(p.id,"""{"inputMode":"PERCENT","components":[{"productId":"${a.id}","percent":"50","yieldFactor":null},{"productId":"${b.id}","percent":"50"}]}""")
        assertThat(result.yieldSource).isEqualTo("DEFAULT"); assertThat(result.effectiveYieldFactor).isEqualTo("1")
        assertThat(result.nutritionPer100g.calories).isEqualByComparingTo("240")
    }
    @Test fun `manual leaf values persist across graph transitions and decimal fractions are exact`() {
        val p = leaf("75"); val children = List(3) { leaf("100") }.sortedBy { it.id.toString() }
        val values = listOf("33.3333333334","33.3333333333","33.3333333333")
        val body = children.zip(values).joinToString(",") { (child,percent) -> """{"productId":"${child.id}","percent":"$percent"}""" }
        var result = write(p.id,"""{"inputMode":"PERCENT","manualYieldFactor":"0.8","components":[$body]}""")
        assertThat(result.manualNutrition!!.calories).isEqualByComparingTo("75")
        assertThat(result.components.map { it.fraction.toPlainString() }).containsExactly("0.333333333334","0.333333333333","0.333333333333")
        result = catalog.writeComposition(actor,p.id,input())
        assertThat(result.nutritionPer100g.calories).isEqualByComparingTo("75")
        assertThat(result.effectiveYieldFactor).isEqualTo("1")
    }
    @Test fun `direct indirect and concurrent cycles are rejected atomically`() {
        val a = leaf(); val b = leaf(); val c = leaf()
        failure("CYCLE_DETECTED") { catalog.writeComposition(actor,a.id,input(a.id to "1")) }
        catalog.writeComposition(actor,a.id,input(b.id to "1",manual="0.8"))
        catalog.writeComposition(actor,b.id,input(c.id to "1"))
        failure("CYCLE_DETECTED") { catalog.writeComposition(actor,c.id,input(a.id to "1")) }
        assertThat(catalog.get(actor,c.id).kind).isEqualTo("LEAF")
        assertThat(catalog.get(actor,a.id).manualYieldFactor!!.toBigDecimal()).isEqualByComparingTo("0.8")
        val x = leaf(); val y = leaf(); val start=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val outcomes = listOf(x.id to y.id,y.id to x.id).map { (parent,child) -> pool.submit<String> {
                start.await(); try { catalog.writeComposition(actor,parent,input(child to "1")); "OK" } catch (e: CatalogFailure) { e.code }
            } }
            start.countDown()
            assertThat(outcomes.map { it.get(6,TimeUnit.SECONDS) }).containsExactlyInAnyOrder("OK","CYCLE_DETECTED")
        } finally { pool.shutdownNow() }
    }
    @Test fun `ownership deleted existing edges and base graph restrictions are enforced after locks`() {
        val child = leaf(); val foreign = leaf(owner=other); val parent = composite(child.id to "1")
        failure("NOT_FOUND") { catalog.writeComposition(actor,parent.id,input(foreign.id to "1")) }
        catalog.delete(actor,child.id,"KEEP_REFERENCES")
        assertThat(catalog.writeComposition(actor,parent.id,input(child.id to "1")).nutritionPer100g.calories).isEqualByComparingTo("400")
        failure("NOT_FOUND") { composite(child.id to "1") }
        failure("NOT_FOUND") { catalog.writeComposition(other,parent.id,input(foreign.id to "1")) }
        val admin = actor.copy(role=Role.ADMIN)
        val base = catalog.create(admin,"Base graph ${UUID.randomUUID()}",Nutrition(BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),true)
        failure("NOT_FOUND") { catalog.writeComposition(admin,base.id,input(foreign.id to "1"),true) }
        failure("NOT_FOUND") { catalog.writeComposition(admin,base.id,input(leaf().id to "1"),true) }
        assertThat(catalog.get(actor,base.id).kind).isEqualTo("LEAF")
    }
    @Test fun `calculation response reads a single repeatable snapshot while a leaf is updated`() {
        val child=leaf("100"); val parent=composite(child.id to "1")
        val pool=Executors.newSingleThreadExecutor()
        try {
            transactions.snapshot { snapshot ->
                assertThat(catalog.nutrition(actor,listOf(parent.id),snapshot).getValue(parent.id).nutritionPer100g.calories).isEqualByComparingTo("100")
                pool.submit { catalog.patch(actor,child.id,null,Nutrition(BigDecimal("200"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)) }.get(4,TimeUnit.SECONDS)
                assertThat(catalog.nutrition(actor,listOf(parent.id),snapshot).getValue(parent.id).nutritionPer100g.calories).isEqualByComparingTo("100")
                assertThat(catalog.getVisible(actor,listOf(parent.id)).single().nutritionPer100g.calories).isEqualByComparingTo("100")
                true
            }
            assertThat(catalog.get(actor,parent.id).nutritionPer100g.calories).isEqualByComparingTo("200")
        } finally { pool.shutdownNow() }
    }
    @Test fun `corrupted persisted cycle returns error rather than partial nutrition`() {
        val a=leaf(); val b=leaf()
        try {
            rawEdge(a.id,b.id,"1"); rawEdge(b.id,a.id,"1")
            failure("CYCLE_DETECTED") { catalog.get(actor,a.id) }
            failure("CYCLE_DETECTED") { transactions.snapshot { catalog.allocateLeaves(actor,listOf(CatalogPortion(0,a.id,BigDecimal.ONE)),it) } }
        } finally { jdbc.sql("DELETE FROM calorica.product_components WHERE parent_id IN (:ids)").param("ids",listOf(a.id,b.id)).update() }
    }
    private fun rawEdge(parent: UUID,child: UUID,fraction: String) {
        jdbc.sql("INSERT INTO calorica.product_components(parent_id,child_id,fraction) VALUES (:parent,:child,CAST(:fraction AS numeric))")
            .param("parent",parent).param("child",child).param("fraction",fraction).update()
    }
    private fun rawNode(yield: String? = null): UUID {
        val id=UUID.randomUUID()
        jdbc.sql("""INSERT INTO calorica.products(id,owner_id,name,manual_calories,manual_protein,manual_fat,manual_carbs,manual_yield_factor)
            VALUES (:id,:owner,'Raw isolated graph test',400,10,2,3,CAST(:yield AS numeric))""")
            .param("id",id).param("owner",actor.id).param("yield",yield,java.sql.Types.VARCHAR).update()
        return id
    }
    @Test fun `path guard includes repeated portions and rejects 100001st expansion`() {
        var previous=listOf(rawNode(),rawNode())
        // 16 layers are a tiny DAG with >100000 paths from one root.
        repeat(16) {
            val next=listOf(rawNode(),rawNode())
            next.forEach { p -> previous.forEach { c -> rawEdge(p,c,"0.5") } }; previous=next
        }
        failure("CALCULATION_LIMIT") { catalog.get(actor,previous.first()) }
        var smaller=listOf(rawNode(),rawNode())
        repeat(6) { val next=listOf(rawNode(),rawNode()); next.forEach { p -> smaller.forEach { c -> rawEdge(p,c,"0.5") } }; smaller=next }
        val root=smaller.first()
        assertThat(catalog.get(actor,root).nutritionPer100g.calories).isEqualByComparingTo("400")
        failure("CALCULATION_LIMIT") { transactions.snapshot { snapshot ->
            catalog.allocateLeaves(actor,(0..999).map { CatalogPortion(it,root,BigDecimal.ONE) },snapshot)
        } }
    }
    @Test fun `numeric overflow and excessive intermediate scale are calculation limits without partial result`() {
        var current=rawNode()
        repeat(10) { val parent=rawNode("0.000000000001");rawEdge(parent,current,"1");current=parent }
        failure("CALCULATION_LIMIT") { catalog.get(actor,current) }
        // Weights fit below 100 integer digits, but the final nutrient value exceeds the bound.
        current=rawNode()
        jdbc.sql("UPDATE calorica.products SET manual_calories=99999.99 WHERE id=:id").param("id",current).update()
        repeat(8) { val parent=rawNode("0.000000000001");rawEdge(parent,current,"1");current=parent }
        failure("CALCULATION_LIMIT") { catalog.get(actor,current) }
        current=rawNode()
        repeat(100) { val parent=rawNode("9999.999999999999");rawEdge(parent,current,"1");current=parent }
        failure("CALCULATION_LIMIT") { catalog.get(actor,current) }
    }
    @Test fun `node guard rejects over ten thousand unique calculation nodes`() {
        failure("CALCULATION_LIMIT") { transactions.snapshot { catalog.nutrition(actor,List(10001) { UUID.randomUUID() },it) } }
        val root=rawNode(); val salt=UUID.randomUUID().toString()
        // A shallow valid tree: 1 root + 100 composites + 10000 leaves. One SQL insert per level,
        // not 10000 application calls. No individual node exceeds the 100-component limit.
        jdbc.sql("""INSERT INTO calorica.products(id,owner_id,name,manual_calories,manual_protein,manual_fat,manual_carbs)
            SELECT md5(:salt || 'c' || n)::uuid,:owner,'Node limit composite',1,1,1,1 FROM generate_series(1,100) n
            UNION ALL SELECT md5(:salt || 'l' || n)::uuid,:owner,'Node limit leaf',1,1,1,1 FROM generate_series(1,10000) n""")
            .param("salt",salt).param("owner",actor.id).update()
        jdbc.sql("""INSERT INTO calorica.product_components(parent_id,child_id,fraction)
            SELECT :root,md5(:salt || 'c' || n)::uuid,0.01 FROM generate_series(1,100) n
            UNION ALL SELECT md5(:salt || 'c' || ((n-1)/100+1))::uuid,md5(:salt || 'l' || n)::uuid,0.01 FROM generate_series(1,10000) n""")
            .param("root",root).param("salt",salt).update()
        failure("CALCULATION_LIMIT") { catalog.get(actor,root) }
    }
}
