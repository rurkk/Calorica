package ru.calorica.catalog.labels

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.calorica.catalog.api.labels.ProductLabelInput
import ru.calorica.catalog.domain.CatalogFailure
import java.util.UUID

class ProductLabelValidationTest {
    private val mapper = jacksonObjectMapper()
    @Test fun `names trim and count Unicode code points without truncation`() {
        assertThat(ProductLabelInput.name(mapper.readTree("""{"name":"  Молоко  "}"""))).isEqualTo("Молоко")
        val boundary = "🍎".repeat(200)
        assertThat(ProductLabelInput.name(mapper.valueToTree(mapOf("name" to boundary)))).isEqualTo(boundary)
        for (name in listOf("  ", "🍎".repeat(201))) assertThatThrownBy {
            ProductLabelInput.name(mapper.valueToTree(mapOf("name" to name)))
        }.isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(422) }
    }
    @Test fun `unknown fields malformed roots and non text values are bad requests`() {
        for (json in listOf("null", "[]", "1", """{"name":1}""", """{"name":"Milk","ownerId":"x"}"""))
            assertThatThrownBy { ProductLabelInput.name(mapper.readTree(json)) }
                .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(400) }
        for (json in listOf("{}", """{"name":null}""")) assertThatThrownBy { ProductLabelInput.name(mapper.readTree(json)) }
            .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(422) }
    }
    @Test fun `assignment is an explicit unique set bounded at one hundred`() {
        assertThat(ProductLabelInput.assignment(mapper.readTree("""{"labelIds":[]}"""))).isEmpty()
        val ids = List(100) { UUID.randomUUID() }
        assertThat(ProductLabelInput.assignment(mapper.valueToTree(mapOf("labelIds" to ids)))).isEqualTo(ids)
        for (values in listOf(ids + UUID.randomUUID(), listOf(ids[0], ids[0])))
            assertThatThrownBy { ProductLabelInput.assignment(mapper.valueToTree(mapOf("labelIds" to values))) }
                .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(422) }
    }
    @Test fun `assignment rejects missing null malformed UUID and mixed JSON types`() {
        for (json in listOf("{}", """{"labelIds":null}""")) assertThatThrownBy {
            ProductLabelInput.assignment(mapper.readTree(json))
        }.isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(422) }
        for (json in listOf("[]", """{"labelIds":"x"}""", """{"labelIds":[null]}""", """{"labelIds":[1]}""",
            """{"labelIds":["1-1-1-1-1"]}""", """{"labelIds":[],"name":"x"}"""))
            assertThatThrownBy { ProductLabelInput.assignment(mapper.readTree(json)) }
                .isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(400) }
    }
}
