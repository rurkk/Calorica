package ru.calorica.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import org.springframework.jdbc.core.simple.JdbcClient
import ru.calorica.accounts.application.AccountsService
import ru.calorica.accounts.domain.Credentials
import java.math.BigDecimal
import java.util.UUID

/** Exercises T07/T09/T12 together through real authenticated HTTP and PostgreSQL. */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MvpDiaryCatalogIntegrationTest @Autowired constructor(
    private val http: TestRestTemplate, private val mapper: ObjectMapper,
    private val accounts: AccountsService, private val jdbc: JdbcClient,
) {
    @Test fun `diary shares current recursive nutrition and explicit unit conversion without duplicating labels`() {
        val user=accounts.register(Credentials("p2-${UUID.randomUUID()}@example.org","integrated test password8"))
        val admin=accounts.register(Credentials("p2-${UUID.randomUUID()}@example.org","integrated test password8"))
        jdbc.sql("UPDATE calorica.accounts SET role='ADMIN' WHERE id=:id").param("id",admin.account.id).update()
        fun request(token: String,method: HttpMethod,path: String,body: Any?=null,expected: Int=200): JsonNode? {
            val headers=HttpHeaders().apply { contentType=MediaType.APPLICATION_JSON; setBearerAuth(token) }
            val result=http.exchange("/api/v1$path",method,HttpEntity(body?.let(mapper::writeValueAsString),headers),String::class.java)
            assertThat(result.statusCode.value()).describedAs("%s %s: %s",method,path,result.body).isEqualTo(expected)
            return result.body?.takeIf { it.isNotBlank() }?.let(mapper::readTree)
        }
        fun nutrition(calories: String)=mapOf("calories" to calories,"protein" to "0","fat" to "0","carbs" to "0")
        fun base(calories: String): String = request(admin.accessToken,HttpMethod.POST,"/admin/products",
            mapOf("name" to "P2-${UUID.randomUUID()}","manualNutrition" to nutrition(calories)),201)!!["id"].asText()
        val first=base("400"); val second=base("80")
        val composite=request(user.accessToken,HttpMethod.POST,"/products",mapOf("name" to "P2 состав",
            "composition" to mapOf("inputMode" to "PERCENT","manualYieldFactor" to "0.8","components" to listOf(
                mapOf("productId" to first,"percent" to "60"),mapOf("productId" to second,"percent" to "40")))),201)!!["id"].asText()
        val labels=request(user.accessToken,HttpMethod.GET,"/meal-labels")!!.take(2).map { it["id"].asText() }
        assertThat(labels).hasSize(2)
        fun diaryBody(product: String,quantity: String,unit: String)=mapOf("productId" to product,"quantity" to quantity,
            "unit" to unit,"occurredAt" to "2026-11-03T08:00:00Z","mealLabelIds" to labels)
        val entry=request(user.accessToken,HttpMethod.POST,"/diary/entries",diaryBody(composite,"200","g"),201)!!["id"].asText()
        fun total(): BigDecimal = request(user.accessToken,HttpMethod.GET,"/diary?date=2026-11-03&zone=Europe/Moscow")!!["total"]["calories"].asText().toBigDecimal()
        assertThat(total()).isEqualByComparingTo("680")
        request(admin.accessToken,HttpMethod.PATCH,"/admin/products/$first",mapOf("manualNutrition" to nutrition("500")))
        assertThat(total()).isEqualByComparingTo("830")
        request(admin.accessToken,HttpMethod.PUT,"/admin/products/$first/units/ml",mapOf("gramsPerUnit" to "1.03"))
        val millilitres=request(user.accessToken,HttpMethod.POST,"/diary/entries",diaryBody(first,"200","ml"),201)!!
        assertThat(millilitres["consumedGrams"].asText().toBigDecimal()).isEqualByComparingTo("206")
        assertThat(total()).isEqualByComparingTo("1860")
        request(user.accessToken,HttpMethod.PATCH,"/diary/entries/$entry",mapOf("quantity" to "100","unit" to "g","mealLabelIds" to emptyList<String>()))
        assertThat(total()).isEqualByComparingTo("1445")
        request(admin.accessToken,HttpMethod.DELETE,"/admin/products/$first?mode=KEEP_REFERENCES",expected=204)
        request(user.accessToken,HttpMethod.PATCH,"/diary/entries/${millilitres["id"].asText()}",mapOf("quantity" to "100","unit" to "g"))
        assertThat(total()).isEqualByComparingTo("915")
        request(user.accessToken,HttpMethod.POST,"/diary/entries",diaryBody(first,"1","g"),404)
        val columns=jdbc.sql("""SELECT column_name FROM information_schema.columns
            WHERE table_schema='calorica' AND table_name='diary_entries'""").query(String::class.java).list()
        assertThat(columns).doesNotContain("unit","quantity","calories","nutrition_snapshot")
    }
}
