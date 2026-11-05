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
import ru.calorica.catalog.application.CatalogService
import ru.calorica.catalog.application.CatalogTransactions
import ru.calorica.catalog.application.units.CatalogUnits
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.domain.Nutrition
import ru.calorica.platform.security.Actor
import ru.calorica.platform.security.Role
import java.math.BigDecimal
import java.util.UUID

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnitsIntegrationTest @Autowired constructor(
    private val http: TestRestTemplate, private val mapper: ObjectMapper, private val jdbc: JdbcClient,
    private val accounts: AccountsService, private val catalog: CatalogService,
    private val transactions: CatalogTransactions, private val units: CatalogUnits,
) {
    data class User(val actor: Actor, val token: String)
    private lateinit var a: User
    private lateinit var b: User
    private lateinit var admin: User
    @BeforeAll fun users() {
        fun user(role: Role = Role.USER): User {
            val tokens = accounts.register(Credentials("t12-${UUID.randomUUID()}@example.org", "units test password8"))
            if (role == Role.ADMIN) jdbc.sql("UPDATE calorica.accounts SET role='ADMIN' WHERE id=:id")
                .param("id", tokens.account.id).update()
            val session = jdbc.sql("SELECT id FROM calorica.sessions WHERE account_id=:id")
                .param("id", tokens.account.id).query(UUID::class.java).single()
            return User(Actor(tokens.account.id, role, session), tokens.accessToken)
        }
        a=user(); b=user(); admin=user(Role.ADMIN)
    }
    private fun product(user: User = admin, base: Boolean = true): UUID = catalog.create(user.actor,
        "T12-${UUID.randomUUID()}", Nutrition(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO), base).id
    private fun request(user: User?, method: HttpMethod, path: String, body: String? = null): ResponseEntity<String> =
        http.exchange("/api/v1$path", method, HttpEntity(body, HttpHeaders().apply {
            contentType=MediaType.APPLICATION_JSON; user?.let { setBearerAuth(it.token) }
        }), String::class.java)
    private fun json(response: ResponseEntity<String>): JsonNode = mapper.readTree(response.body)
    private fun checkCode(response: ResponseEntity<String>, status: Int, code: String) {
        assertThat(response.statusCode.value()).isEqualTo(status)
        assertThat(json(response)["code"].asText()).isEqualTo(code)
        assertThat(json(response)["correlationId"].asText()).isEqualTo(response.headers.getFirst("X-Request-Id"))
    }

    @Test fun `grams are implicit and ml conversion is explicit Admin controlled decimal data`() {
        val id=product()
        val path="/products/$id/units"
        val adminPath="/admin/products/$id/units/ml"
        checkCode(request(null,HttpMethod.GET,path),401,"UNAUTHORIZED")
        assertThat(json(request(a,HttpMethod.GET,path)).map { it["code"].asText() }).containsExactly("g")
        checkCode(request(a,HttpMethod.PUT,adminPath,"""{"gramsPerUnit":"1.03"}"""),403,"FORBIDDEN")
        val response=request(admin,HttpMethod.PUT,adminPath,"""{"gramsPerUnit":"1.03"}""")
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(json(response)["gramsPerUnit"].isTextual).isTrue()
        assertThat(json(response)["gramsPerUnit"].asText().toBigDecimal()).isEqualByComparingTo("1.03")
        assertThat(json(request(b,HttpMethod.GET,path)).map { it["code"].asText() }).containsExactly("g","ml")
        val grams=transactions.mutation { units.convert(a.actor,id,BigDecimal("200"),"ml",it) }
        assertThat(grams).isEqualTo(BigDecimal("206.000"))
        assertThat(request(admin,HttpMethod.DELETE,adminPath).statusCode.value()).isEqualTo(204)
        assertThat(units.list(a.actor,id).map { it.code }).containsExactly("g")
        assertThatThrownBy { transactions.mutation { units.convert(a.actor,id,BigDecimal.ONE,"ml",it) } }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("UNIT_UNAVAILABLE") }
    }

    @Test fun `personal products never infer ml and ownership is checked on reads and conversion`() {
        val id=product(a,false)
        assertThat(units.list(a.actor,id).map { it.code }).containsExactly("g")
        checkCode(request(b,HttpMethod.GET,"/products/$id/units"),404,"NOT_FOUND")
        checkCode(request(admin,HttpMethod.PUT,"/admin/products/$id/units/ml","""{"gramsPerUnit":"1"}"""),404,"NOT_FOUND")
        assertThatThrownBy { transactions.mutation { units.convert(a.actor,id,BigDecimal.ONE,"ml",it) } }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("UNIT_UNAVAILABLE") }
        assertThatThrownBy { transactions.mutation { units.convert(b.actor,id,BigDecimal.ONE,"g",it) } }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(404) }
        catalog.delete(a.actor,id,"KEEP_REFERENCES")
        assertThat(transactions.mutation { units.convert(a.actor,id,BigDecimal("5"),"g",it) })
            .isEqualTo(BigDecimal("5.000"))
    }

    @Test fun `invalid factors and unknown fields fail atomically before NUMERIC rounding`() {
        val id=product()
        val path="/admin/products/$id/units/ml"
        for ((body,status,code) in listOf(
            Triple("""{"gramsPerUnit":"0"}""",422,"VALIDATION_FAILED"),
            Triple("""{"gramsPerUnit":"1.0000000000001"}""",422,"PRECISION_EXCEEDED"),
            Triple("""{"gramsPerUnit":1.03}""",400,"BAD_REQUEST"),
            Triple("""{"gramsPerUnit":null}""",422,"VALIDATION_FAILED"),
            Triple("""{"gramsPerUnit":"1","ownerId":"${a.actor.id}"}""",400,"BAD_REQUEST"))) {
            checkCode(request(admin,HttpMethod.PUT,path,body),status,code)
            assertThat(units.list(a.actor,id).map { it.code }).containsExactly("g")
        }
        assertThatThrownBy { transactions.mutation { units.convert(a.actor,id,BigDecimal.ONE,"kg",it) } }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.code).isEqualTo("UNIT_UNAVAILABLE") }
    }
}
