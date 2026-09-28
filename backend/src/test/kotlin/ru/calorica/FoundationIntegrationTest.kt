package ru.calorica

import com.fasterxml.jackson.databind.ObjectMapper
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.simple.JdbcClient
import javax.sql.DataSource

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIntegrationTest @Autowired constructor(
    private val http: TestRestTemplate,
    private val jdbc: JdbcClient,
    private val dataSource: DataSource,
    private val mapper: ObjectMapper,
) {
    @Test
    fun `health is public minimal and database backed`() {
        for (path in listOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            val response = http.getForEntity(path, String::class.java)
            assertThat(response.statusCode.value()).isEqualTo(200)
            val health = mapper.readTree(response.body)
            assertThat(health["status"].asText()).isEqualTo("UP")
            assertThat(health.has("components")).isFalse()
            assertThat(health.has("details")).isFalse()
            if (path != "/actuator/health") {
                assertThat(health).isEqualTo(mapper.readTree("""{"status":"UP"}"""))
            }
        }
    }

    @Test
    fun `future data endpoints and management remain closed even with fabricated credentials`() {
        for (path in listOf("/api/products", "/api/diary", "/actuator/env", "/error", "/login")) {
            for (method in listOf(HttpMethod.GET, HttpMethod.POST)) {
                val headers = HttpHeaders().apply { setBearerAuth("not-a-valid-token") }
                val response = http.exchange(path, method, HttpEntity<String>(headers), String::class.java)
                assertThat(response.statusCode.value()).isEqualTo(401)
                assertThat(response.headers.contentType.toString()).startsWith("application/problem+json")
                val error = mapper.readTree(response.body)
                assertThat(error["code"].asText()).isEqualTo("UNAUTHORIZED")
                assertThat(error["correlationId"].asText()).isEqualTo(response.headers.getFirst("X-Request-Id"))
                assertThat(response.body).doesNotContain("not-a-valid-token", "exception", "trace")
                assertThat(response.headers.getFirst("Set-Cookie")).isNull()
            }
        }
    }

    @Test
    fun `initial SQL migration is applied and safely revalidated and repeated`() {
        // A username matching the new schema must not move Liquibase history on restart.
        assertThat(jdbc.sql("SELECT current_schema()").query(String::class.java).single()).isEqualTo("public")
        assertThat(jdbc.sql("SELECT count(*) FROM public.databasechangelog WHERE id = '001-foundation'")
            .query(Int::class.java).single()).isEqualTo(1)
        val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(dataSource.connection))
        Liquibase("db/changelog/db.changelog-master.yaml", ClassLoaderResourceAccessor(), database).use {
            it.validate()
            it.update(liquibase.Contexts(), liquibase.LabelExpression())
        }
        assertThat(jdbc.sql("SELECT count(*) FROM public.databasechangelog").query(Int::class.java).single()).isEqualTo(1)
        assertThat(jdbc.sql("SELECT count(*) FROM pg_namespace WHERE nspname = 'calorica'")
            .query(Int::class.java).single()).isEqualTo(1)
    }
}
