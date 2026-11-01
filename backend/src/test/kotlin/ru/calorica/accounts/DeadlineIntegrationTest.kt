package ru.calorica.accounts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.*
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import ru.calorica.accounts.application.AccountsService
import ru.calorica.accounts.domain.Credentials
import ru.calorica.platform.security.Actor
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DeadlineIntegrationTest.ProbeConfiguration::class)
class DeadlineIntegrationTest @Autowired constructor(
    private val accounts: AccountsService, private val http: TestRestTemplate,
    private val jdbc: JdbcClient, private val probe: SlowProbe,
) {
    @LocalServerPort private var port: Int = 0
    @Test fun `deadline returns 503 before late work and expired transaction rolls back`() {
        val user = accounts.register(Credentials("deadline-${UUID.randomUUID()}@example.org","password8"))
        val start = System.nanoTime()
        val headers = HttpHeaders().apply { setBearerAuth(user.accessToken) }
        val response = http.exchange("/api/v1/test/deadline",HttpMethod.GET,HttpEntity<String>(headers),String::class.java)
        assertThat(response.statusCode.value()).isEqualTo(503)
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isBetween(7500,10000)
        assertThat(response.body).contains("SERVICE_UNAVAILABLE")
        // Immediately use HTTP again while the late handler is still running; it must retain its own request.
        assertThat(http.exchange("/api/v1/accounts/me",HttpMethod.GET,HttpEntity<String>(headers),String::class.java).statusCode.value()).isEqualTo(200)
        check(probe.finished.await(5,TimeUnit.SECONDS))
        assertThat(jdbc.sql("SELECT calorie_target FROM calorica.profiles WHERE account_id=:id").param("id",user.account.id)
            .query { rs, _ -> rs.getBigDecimal(1) }.optional().orElse(null)).isNull()
        assertThat(http.exchange("/api/v1/accounts/me",HttpMethod.GET,HttpEntity<String>(headers),String::class.java).statusCode.value()).isEqualTo(200)
    }
    @Test fun `chunked oversized request is rejected without Content-Length`() {
        Socket("127.0.0.1",port).use { socket ->
            socket.soTimeout = 11000
            val out = socket.getOutputStream()
            val payload = "a".repeat(1024*1024+1)
            out.write(("POST /api/v1/accounts/login HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n" + payload.length.toString(16) + "\r\n" + payload + "\r\n0\r\n\r\n").toByteArray())
            out.flush()
            assertThat(socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()).contains("413")
        }
    }
    @Test fun `four incomplete bodies expire without occupying application workers`() {
        val sockets = List(4) { Socket("127.0.0.1",port).apply {
            soTimeout = 11000
            getOutputStream().write(("POST /api/v1/accounts/login HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                "Content-Length: 1000\r\nConnection: close\r\n\r\n{").toByteArray())
            getOutputStream().flush()
        } }
        try {
            val user = accounts.register(Credentials("slowbody-${UUID.randomUUID()}@example.org","password8"))
            val headers = HttpHeaders().apply { setBearerAuth(user.accessToken) }
            val start = System.nanoTime()
            assertThat(http.exchange("/api/v1/accounts/me",HttpMethod.GET,HttpEntity<String>(headers),String::class.java).statusCode.value()).isEqualTo(200)
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(2000)
            for (socket in sockets)
                assertThat(socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()).contains("503")
            assertThat(http.exchange("/api/v1/accounts/me",HttpMethod.GET,HttpEntity<String>(headers),String::class.java).statusCode.value()).isEqualTo(200)
        } finally { sockets.forEach { it.close() } }
    }
    @TestConfiguration class ProbeConfiguration {
        @Bean fun slowProbe(jdbc: JdbcClient, manager: PlatformTransactionManager) = SlowProbe(jdbc, manager)
    }
    @RestController class SlowProbe(private val jdbc: JdbcClient, private val manager: PlatformTransactionManager) {
        val finished = CountDownLatch(1)
        @GetMapping("/api/v1/test/deadline")
        fun slow(@AuthenticationPrincipal actor: Actor): Map<String,String> {
            try {
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(9)
                // Deliberately ignores interrupt to verify the deadline check at the actual commit boundary.
                while (System.nanoTime() < until) { LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); Thread.interrupted() }
                TransactionTemplate(manager).executeWithoutResult {
                    jdbc.sql("UPDATE calorica.profiles SET calorie_target=1 WHERE account_id=:id").param("id",actor.id).update()
                }
                return mapOf("result" to "late")
            } finally { finished.countDown() }
        }
    }
}
