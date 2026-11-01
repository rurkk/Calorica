package ru.calorica.accounts

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
import org.springframework.transaction.support.TransactionTemplate
import ru.calorica.accounts.application.AccountsService
import ru.calorica.accounts.domain.Credentials
import ru.calorica.accounts.domain.Tokens
import ru.calorica.accounts.persistence.AccountRepository
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.security.Role
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountsIntegrationTest @Autowired constructor(
    private val http: TestRestTemplate, private val mapper: ObjectMapper, private val jdbc: JdbcClient,
    private val accounts: AccountsService, private val repository: AccountRepository,
    private val manager: PlatformTransactionManager,
) {
    private fun register() = accounts.register(Credentials("t03-${UUID.randomUUID()}@example.org", "test password 8"))
    private fun request(method: HttpMethod, path: String, body: String? = null, token: String? = null,
                        forwarded: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (token != null) setBearerAuth(token)
            if (forwarded != null) set("X-Forwarded-For", forwarded)
        }
        return http.exchange("/api/v1$path", method, HttpEntity(body, headers), String::class.java)
    }
    private fun me(token: String) = request(HttpMethod.GET, "/accounts/me", token = token)
    private fun json(response: ResponseEntity<String>): JsonNode = mapper.readTree(response.body)
    private fun code(response: ResponseEntity<String>, status: Int, code: String) {
        assertThat(response.statusCode.value()).isEqualTo(status)
        assertThat(response.headers.contentType.toString()).startsWith("application/problem+json")
        assertThat(json(response)["code"].asText()).isEqualTo(code)
        assertThat(json(response)["correlationId"].asText()).isEqualTo(response.headers.getFirst("X-Request-Id"))
        assertThat(response.body).doesNotContain("password_hash", "stackTrace", "test password 8")
    }
    @Test @Order(1) fun `registration creates normalized USER empty profile and only hashes at rest`() {
        val email = "Case-${UUID.randomUUID()}@Example.org"
        val response = request(HttpMethod.POST, "/accounts/register", """{"email":" $email ","password":"test password 8"}""")
        assertThat(response.statusCode.value()).isEqualTo(201)
        assertThat(response.headers.cacheControl).contains("no-store")
        assertThat(response.headers.getFirst("Set-Cookie")).isNull()
        val result = json(response)
        assertThat(result["account"]["email"].asText()).isEqualTo(email.lowercase())
        assertThat(result["account"]["role"].asText()).isEqualTo("USER")
        assertThat(result["account"]["profile"].fields().asSequence().all { it.value.isNull }).isTrue()
        assertThat(Duration.between(Instant.now(), Instant.parse(result["accessExpiresAt"].asText())).seconds).isBetween(895,900)
        assertThat(Duration.between(Instant.now(), Instant.parse(result["sessionExpiresAt"].asText())).toDays()).isEqualTo(29)
        val id = UUID.fromString(result["account"]["id"].asText())
        val hash = jdbc.sql("SELECT password_hash FROM calorica.accounts WHERE id=:id").param("id",id).query(String::class.java).single()
        assertThat(hash).startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$")
        val raw = result["refreshToken"].asText()
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.refresh_tokens WHERE token_hash=:raw").param("raw",raw).query(Int::class.java).single()).isZero()
        code(request(HttpMethod.POST, "/accounts/register", """{"email":"${email.lowercase()}","password":"different 8"}"""),409,"EMAIL_CONFLICT")
    }
    @Test @Order(2) fun `invalid JSON validation and self elevation are rejected safely`() {
        val attack = """{"email":"a@example.org","password":"password8","role":"ADMIN"}"""
        code(request(HttpMethod.POST,"/accounts/register",attack),400,"BAD_REQUEST")
        code(request(HttpMethod.POST,"/accounts/login","""{"email":7,"password":"password8"}"""),400,"BAD_REQUEST")
        code(request(HttpMethod.POST,"/accounts/login","""{"email":"bad","password":"password8"}"""),422,"VALIDATION_FAILED")
        code(request(HttpMethod.POST,"/accounts/login","""{"email":"a@example.org","password":"short"}"""),422,"VALIDATION_FAILED")
        code(request(HttpMethod.POST,"/accounts/login","""{"email":"a@example.org","email":"b@example.org","password":"password8"}"""),400,"BAD_REQUEST")
        code(request(HttpMethod.POST,"/accounts/login","""{"email":"missing@example.org","password":"wrong pass8"}"""),401,"UNAUTHORIZED")
        code(request(HttpMethod.GET,"/accounts/me"),401,"UNAUTHORIZED")
        code(request(HttpMethod.GET,"/accounts/me",token="forged"),401,"UNAUTHORIZED")
    }
    @Test @Order(3) fun `profile PATCH preserves absent clears NULL validates exact decimal strings and isolates accounts`() {
        val a = register(); val b = register()
        val first = request(HttpMethod.PATCH,"/profile","""{"calorieTarget":"2000.25","proteinTarget":"0","fatTarget":"99999.99"}""",a.accessToken)
        assertThat(first.statusCode.value()).isEqualTo(200)
        val next = request(HttpMethod.PATCH,"/profile","""{"proteinTarget":null}""",a.accessToken)
        assertThat(json(next)["calorieTarget"].asText()).isEqualTo("2000.25")
        assertThat(json(next)["proteinTarget"].isNull).isTrue()
        assertThat(json(request(HttpMethod.PATCH,"/profile","{}",a.accessToken))).isEqualTo(json(next))
        assertThat(json(me(b.accessToken))["profile"].fields().asSequence().all { it.value.isNull }).isTrue()
        code(request(HttpMethod.PATCH,"/profile","""{"accountId":"${b.account.id}","calorieTarget":"1"}""",a.accessToken),400,"BAD_REQUEST")
        for (body in listOf("""{"calorieTarget":12}""","[]"))
            code(request(HttpMethod.PATCH,"/profile",body,a.accessToken),400,"BAD_REQUEST")
        for (value in listOf("-1","1e2","1.001","100000","NaN"," 1","01"))
            code(request(HttpMethod.PATCH,"/profile","""{"calorieTarget":"$value"}""",a.accessToken),422,"VALIDATION_FAILED")
        code(request(HttpMethod.GET,"/profile/${b.account.id}",token=a.accessToken),404,"NOT_FOUND")
    }
    @Test @Order(4) fun `refresh rotates absolute expiry replay commits revocation of old and successor access`() {
        val first = register()
        val next = accounts.refresh(first.refreshToken)
        assertThat(next.sessionExpiresAt).isEqualTo(first.sessionExpiresAt)
        assertThat(next.refreshToken).isNotEqualTo(first.refreshToken)
        assertThat(me(next.accessToken).statusCode.value()).isEqualTo(200)
        code(request(HttpMethod.POST,"/accounts/refresh","""{"refreshToken":"${first.refreshToken}"}"""),401,"UNAUTHORIZED")
        code(me(first.accessToken),401,"UNAUTHORIZED")
        code(me(next.accessToken),401,"UNAUTHORIZED")
        assertThatThrownBy { accounts.refresh(next.refreshToken) }.isInstanceOf(ApiFailure::class.java)
    }
    @Test @Order(5) fun `logout revokes access and refresh of current session only and roles are current`() {
        val a = register()
        val b = accounts.login(Credentials(a.account.email,"test password 8"))
        code(request(HttpMethod.GET,"/admin/not-implemented",token=a.accessToken),403,"FORBIDDEN")
        jdbc.sql("UPDATE calorica.accounts SET role='ADMIN' WHERE id=:id").param("id",a.account.id).update()
        assertThat(accounts.authenticate(a.accessToken).role).isEqualTo(Role.ADMIN)
        code(request(HttpMethod.GET,"/admin/not-implemented",token=a.accessToken),404,"NOT_FOUND")
        jdbc.sql("UPDATE calorica.accounts SET role='USER' WHERE id=:id").param("id",a.account.id).update()
        assertThat(accounts.authenticate(a.accessToken).role).isEqualTo(Role.USER)
        assertThat(request(HttpMethod.POST,"/accounts/logout",token=a.accessToken).statusCode.value()).isEqualTo(204)
        code(me(a.accessToken),401,"UNAUTHORIZED")
        assertThatThrownBy { accounts.refresh(a.refreshToken) }.isInstanceOf(ApiFailure::class.java)
        assertThat(me(b.accessToken).statusCode.value()).isEqualTo(200)
    }
    private fun race(a: () -> Any?, b: () -> Any?): List<Result<Any?>> {
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2); val start = CountDownLatch(1)
        try {
            val futures = listOf(a,b).map { action -> pool.submit(Callable {
                ready.countDown(); check(start.await(5,TimeUnit.SECONDS)); runCatching(action)
            }) }
            check(ready.await(5,TimeUnit.SECONDS)); start.countDown()
            return futures.map { it.get(10,TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
    @Test @Order(6) fun `parallel refresh consumes once and replay revokes session under PostgreSQL row lock`() {
        repeat(3) {
            val first = register()
            val result = race({ accounts.refresh(first.refreshToken) }, { accounts.refresh(first.refreshToken) })
            assertThat(result.count { it.isSuccess }).isEqualTo(1)
            assertThat((result.first { it.isFailure }.exceptionOrNull() as ApiFailure).status).isEqualTo(401)
            val successor = result.first { it.isSuccess }.getOrThrow() as Tokens
            assertThatThrownBy { accounts.authenticate(successor.accessToken) }.isInstanceOf(ApiFailure::class.java)
            assertThatThrownBy { accounts.refresh(successor.refreshToken) }.isInstanceOf(ApiFailure::class.java)
            val id = first.account.id
            assertThat(jdbc.sql("SELECT count(*) FROM calorica.refresh_tokens t JOIN calorica.sessions s ON s.id=t.session_id WHERE s.account_id=:id")
                .param("id",id).query(Int::class.java).single()).isEqualTo(2)
        }
    }
    @Test @Order(7) fun `refresh racing logout cannot leave an active successor`() {
        val first = register(); val actor = accounts.authenticate(first.accessToken)
        val result = race({ accounts.refresh(first.refreshToken) }, { accounts.logout(actor) })
        assertThat(result[1].isSuccess).isTrue()
        assertThatThrownBy { accounts.authenticate(first.accessToken) }.isInstanceOf(ApiFailure::class.java)
        (result[0].getOrNull() as? Tokens)?.let { next ->
            assertThatThrownBy { accounts.authenticate(next.accessToken) }.isInstanceOf(ApiFailure::class.java)
            assertThatThrownBy { accounts.refresh(next.refreshToken) }.isInstanceOf(ApiFailure::class.java)
        }
    }
    @Test @Order(8) fun `unique email is enforced across concurrent real transactions and expiry is absolute`() {
        val sample = register(); val email = "race-${UUID.randomUUID()}@example.org"
        val hash = repository.byId(sample.account.id).passwordHash
        val results = race(
            { TransactionTemplate(manager).execute { repository.create(UUID.randomUUID(),email,hash,Instant.now()) } },
            { TransactionTemplate(manager).execute { repository.create(UUID.randomUUID(),email,hash,Instant.now()) } })
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(jdbc.sql("SELECT count(*) FROM calorica.accounts WHERE email=:email").param("email",email).query(Int::class.java).single()).isEqualTo(1)
        jdbc.sql("UPDATE calorica.sessions SET created_at=now()-interval '31 days', expires_at=now()-interval '1 day' WHERE account_id=:id")
            .param("id",sample.account.id).update()
        assertThatThrownBy { accounts.refresh(sample.refreshToken) }.isInstanceOf(ApiFailure::class.java)
        code(me(sample.accessToken),401,"UNAUTHORIZED")
    }
    @Test @Order(9) fun `size limit applies to JSON with Content-Length`() {
        code(request(HttpMethod.PATCH,"/profile","{\"x\":\"" + "a".repeat(1024*1024) + "\"}"),413,"REQUEST_TOO_LARGE")
    }
    @Test @Order(10) fun `auth throttling returns Retry-After and forwarded header does not bypass it`() {
        repeat(10) { request(HttpMethod.POST,"/accounts/login","{}",forwarded="203.0.113.$it") }
        val response = request(HttpMethod.POST,"/accounts/login","{}",forwarded="203.0.113.123")
        // Previous credential requests already filled the shared register/login bucket.
        code(response,429,"TOO_MANY_REQUESTS")
        assertThat(response.headers.getFirst("Retry-After")!!.toLong()).isBetween(1,60)
        repeat(30) {
            val failed = request(HttpMethod.POST,"/accounts/refresh","""{"refreshToken":"unknown-$it"}""")
            assertThat(failed.statusCode.value()).isIn(401,429)
        }
        val limited = request(HttpMethod.POST,"/accounts/refresh","""{"refreshToken":"unknown"}""")
        code(limited,429,"TOO_MANY_REQUESTS")
        assertThat(limited.headers.getFirst("Retry-After")!!.toLong()).isBetween(1,60)
    }
}
